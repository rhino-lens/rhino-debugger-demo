package io.github.rhinolens.debugdemo.debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;

import org.mozilla.javascript.lens.ArrayElementChange;
import org.mozilla.javascript.lens.ChangeEvent;
import org.mozilla.javascript.lens.CollectionChange;
import org.mozilla.javascript.lens.Lens;
import org.mozilla.javascript.lens.LensExecution;
import org.mozilla.javascript.lens.LensReport;
import org.mozilla.javascript.lens.Mark;
import org.mozilla.javascript.lens.PropertyChange;
import org.mozilla.javascript.lens.ServiceCall;

/**
 * One script run under a line-by-line debugger, built on Rhino's Debugger / DebugFrame API.
 *
 * <p>The script runs on its own thread. Rhino calls {@link DebugFrame#onLineChange} before each
 * line; when a breakpoint or step says so, that thread stops there and waits for a command
 * (continue, step, stop) or an expression to evaluate. Everything else (the web layer) talks to it
 * through {@link #command}, {@link #evaluate} and {@link #setBreakpoints}, and hears about changes
 * through listeners that receive a full {@link SessionState}.
 */
public final class DebugSession implements Debugger {

    public enum Status { RUNNING, PAUSED, FINISHED, FAILED, STOPPED }

    public enum Action { CONTINUE, STEP_INTO, STEP_OVER, STEP_OUT, STOP }

    private enum StepMode { NONE, INTO, OVER, OUT }

    /** Ends the run. An Error, so the script's own try/catch cannot catch it. */
    static final class StopSignal extends Error {
        StopSignal() {
            super("stopped", null, false, false);
        }
    }

    private sealed interface Request permits Resume, Evaluate {
    }

    private record Resume(Action action) implements Request {
    }

    private record Evaluate(String expression, int frameIndex, CompletableFuture<String> answer)
            implements Request {
    }

    private final String id;
    private final String source;
    private final String sourceName;
    private final Map<String, Object> javaObjects;

    private final Set<Integer> breakpoints = ConcurrentHashMap.newKeySet();
    private final Set<Integer> breakableLines = ConcurrentHashMap.newKeySet();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionState>> listeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final AtomicBoolean awaitingCommand = new AtomicBoolean();

    private volatile Status status = Status.RUNNING;
    private volatile boolean stopRequested;
    private volatile String result;
    private volatile String error;
    private volatile SessionState state;
    private volatile LensExecution lens;          // batik-js-lens execution for this run
    private volatile LensReport lensReport;       // its final report
    private volatile int pauseCount;

    // Used only on the script thread.
    private final List<Frame> stack = new ArrayList<>();
    private StepMode stepMode;
    private int stepDepth;
    private boolean inspecting;
    private String pauseReason;

    public DebugSession(String id, String source, String sourceName, Map<String, Object> javaObjects,
                        Collection<Integer> breakpoints, boolean pauseOnStart) {
        this.id = id;
        this.source = source;
        this.sourceName = sourceName;
        this.javaObjects = javaObjects;
        this.breakpoints.addAll(breakpoints);
        this.stepMode = pauseOnStart ? StepMode.INTO : StepMode.NONE;
        this.state = snapshot();
    }

    // ---- control, from any thread ----

    /** Runs the script on the calling thread until it finishes, fails or is stopped. */
    public void run() {
        LensExecution execution = Lens.execution(sourceName, Map.of("session", id));
        lens = execution;
        try {
            result = ScriptRunner.run(source, sourceName, javaObjects, this, console::add);
            status = Status.FINISHED;
            execution.succeeded();
        } catch (StopSignal stop) {
            status = Status.STOPPED;
            execution.stopped();
        } catch (RhinoException e) {
            error = e.details() + " (line " + e.lineNumber() + ")";
            status = Status.FAILED;
            execution.failed(e);
        } catch (Throwable t) {
            error = String.valueOf(t);
            status = Status.FAILED;
            execution.failed(t);
        } finally {
            lensReport = execution.finish(); // never throws
            stack.clear();
            pauseReason = null;
            Request leftover;
            while ((leftover = requests.poll()) != null) {
                if (leftover instanceof Evaluate ev) ev.answer().complete("error: the script is no longer paused");
            }
            publish();
        }
    }

    /** Returns false if the command does not apply now (e.g. stepping while the script runs). */
    public boolean command(Action action) {
        if (action == Action.STOP) {
            Status before = status; // read first: the script thread may stop as soon as it is woken
            stopRequested = true;
            if (awaitingCommand.compareAndSet(true, false)) requests.add(new Resume(Action.STOP));
            return before == Status.RUNNING || before == Status.PAUSED;
        }
        if (!awaitingCommand.compareAndSet(true, false)) return false;
        requests.add(new Resume(action));
        return true;
    }

    /** Evaluates an expression in a paused frame (0 = innermost). */
    public String evaluate(String expression, int frameIndex) {
        if (status != Status.PAUSED) return "error: the script is not paused";
        CompletableFuture<String> answer = new CompletableFuture<>();
        requests.add(new Evaluate(expression, frameIndex, answer));
        try {
            return answer.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "error: evaluation timed out";
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    public void setBreakpoints(Collection<Integer> lines) {
        breakpoints.clear();
        breakpoints.addAll(lines);
        if (status == Status.PAUSED || status == Status.RUNNING) {
            state = withBreakpoints(state);
            listeners.forEach(l -> l.accept(state));
        }
    }

    public String id() {
        return id;
    }

    public Status status() {
        return status;
    }

    public SessionState state() {
        return state;
    }

    public void addListener(Consumer<SessionState> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<SessionState> listener) {
        listeners.remove(listener);
    }

    // ---- Rhino Debugger, called on the script thread ----

    @Override
    public void handleCompilationDone(Context cx, DebuggableScript fnOrScript, String src) {
        if (!inspecting && sourceName.equals(fnOrScript.getSourceName())) collectLines(fnOrScript);
    }

    @Override
    public DebugFrame getFrame(Context cx, DebuggableScript fnOrScript) {
        return inspecting ? null : new Frame(fnOrScript);
    }

    private void collectLines(DebuggableScript script) {
        for (int line : script.getLineNumbers()) breakableLines.add(line);
        for (int i = 0; i < script.getFunctionCount(); i++) collectLines(script.getFunction(i));
    }

    private final class Frame implements DebugFrame {
        final DebuggableScript script;
        Scriptable activation;
        int line;

        Frame(DebuggableScript script) {
            this.script = script;
        }

        @Override
        public void onEnter(Context cx, Scriptable activation, Scriptable thisObj, Object[] args) {
            this.activation = activation;
            stack.add(this);
        }

        @Override
        public void onLineChange(Context cx, int lineNumber) {
            line = lineNumber;
            onLine(cx);
        }

        @Override
        public void onExceptionThrown(Context cx, Throwable ex) {
        }

        @Override
        public void onExit(Context cx, boolean byThrow, Object resultOrException) {
            int last = stack.size() - 1;
            if (last >= 0 && stack.get(last) == this) stack.remove(last);
        }

        String name() {
            if (!script.isFunction()) return "(script)";
            String name = script.getFunctionName();
            return name == null || name.isEmpty() ? "(anonymous)" : name + "()";
        }
    }

    private void onLine(Context cx) {
        if (inspecting) return;
        if (stopRequested) throw new StopSignal();
        int depth = stack.size();
        String reason = switch (stepMode) {
            case INTO -> "step";
            case OVER -> depth <= stepDepth ? "step" : null;
            case OUT -> depth < stepDepth ? "step" : null;
            case NONE -> null;
        };
        if (reason == null && breakpoints.contains(stack.get(depth - 1).line)) reason = "breakpoint";
        if (reason != null) pause(cx, reason);
    }

    private void pause(Context cx, String reason) {
        status = Status.PAUSED;
        pauseCount++;
        pauseReason = reason;
        stepMode = StepMode.NONE;
        awaitingCommand.set(true);
        publish();
        while (true) {
            Request request;
            try {
                request = requests.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopSignal();
            }
            if (request instanceof Evaluate ev) {
                String value = evaluateHere(cx, ev);
                publish(); // the expression may have changed variables
                ev.answer().complete(value);
                continue;
            }
            Action action = ((Resume) request).action();
            if (action == Action.STOP) throw new StopSignal();
            stepMode = switch (action) {
                case STEP_INTO -> StepMode.INTO;
                case STEP_OVER -> StepMode.OVER;
                case STEP_OUT -> StepMode.OUT;
                default -> StepMode.NONE;
            };
            stepDepth = stack.size();
            status = Status.RUNNING;
            pauseReason = null;
            publish();
            return;
        }
    }

    private String evaluateHere(Context cx, Evaluate ev) {
        int index = stack.size() - 1 - ev.frameIndex();
        if (index < 0 || index >= stack.size()) return "error: no such frame";
        inspecting = true;
        try {
            // watches are inspection, not changes the rule made: not observed by Lens
            Object value = Lens.unobserved(
                    () -> cx.evaluateString(stack.get(index).activation, ev.expression(), "<watch>", 1, null));
            return Values.describe(value);
        } catch (RhinoException e) {
            return "error: " + e.details();
        } catch (RuntimeException e) {
            return "error: " + e;
        } finally {
            inspecting = false;
        }
    }

    // ---- state ----

    private void publish() {
        state = snapshot();
        for (Consumer<SessionState> listener : listeners) {
            try {
                listener.accept(state);
            } catch (RuntimeException ignored) {
                // a closed browser tab; its listener removes itself
            }
        }
    }

    private SessionState snapshot() {
        boolean wasInspecting = inspecting;
        inspecting = true;
        try {
            List<SessionState.StackFrameView> frames = new ArrayList<>();
            if (status == Status.PAUSED) {
                for (int i = stack.size() - 1; i >= 0; i--) {
                    Frame f = stack.get(i);
                    frames.add(new SessionState.StackFrameView(f.name(), f.line, variables(f, i == 0)));
                }
            }
            int line = frames.isEmpty() ? 0 : frames.get(0).line();
            LensReport report = currentLensReport();
            return new SessionState(id, status, pauseCount, pauseReason, line, frames, sorted(breakableLines),
                    sorted(breakpoints), List.copyOf(console), result, error, changes(report), summary(report));
        } finally {
            inspecting = wasInspecting;
        }
    }

    private SessionState withBreakpoints(SessionState s) {
        return new SessionState(s.id(), s.status(), s.pauseCount(), s.pauseReason(), s.line(), s.stack(), s.breakableLines(),
                sorted(breakpoints), s.console(), s.result(), s.error(), s.changes(), s.lensSummary());
    }

    // ---- batik-js-lens ----

    private LensReport currentLensReport() {
        if (lensReport != null) return lensReport;
        LensExecution x = lens;
        return x == null ? null : x.report();
    }

    private static List<SessionState.ChangeView> changes(LensReport report) {
        List<SessionState.ChangeView> out = new ArrayList<>();
        if (report == null) return out;
        for (ChangeEvent e : report.events()) {
            if (e instanceof PropertyChange c) {
                out.add(new SessionState.ChangeView(c.line(), c.via().name(), c.target() + "." + c.property(),
                        c.oldValue().text() + " \u2192 " + c.newValue().text()));
            } else if (e instanceof CollectionChange c) {
                out.add(new SessionState.ChangeView(c.line(), "COLLECTION", c.target() + "." + c.method() + args(c.args()),
                        "size " + c.sizeBefore() + " \u2192 " + c.sizeAfter()));
            } else if (e instanceof ArrayElementChange c) {
                out.add(new SessionState.ChangeView(c.line(), "ARRAY", c.target() + "[" + c.index() + "]",
                        c.oldValue().text() + " \u2192 " + c.newValue().text()));
            } else if (e instanceof ServiceCall c) {
                out.add(new SessionState.ChangeView(c.line(), "SERVICE", c.service() + "." + c.method() + args(c.args()),
                        (c.error() != null ? "threw " + c.error() : "\u2192 " + c.result().text())
                                + String.format(" (%.2f ms)", c.durationNanos() / 1e6)));
            } else if (e instanceof Mark m) {
                out.add(new SessionState.ChangeView(m.line(), "MARK", m.label(), ""));
            }
        }
        return out;
    }

    private static String args(List<org.mozilla.javascript.lens.ValueSnapshot> args) {
        List<String> texts = new ArrayList<>();
        args.forEach(a -> texts.add(a.text()));
        return "(" + String.join(", ", texts) + ")";
    }

    private static String summary(LensReport r) {
        if (r == null) return null;
        return String.format("%d change%s \u00b7 %d service call%s \u00b7 %s in %.1f ms \u00b7 Lens overhead %.2f ms",
                r.changeCount(), r.changeCount() == 1 ? "" : "s",
                r.stats().count(ChangeEvent.Kind.SERVICE_CALL), r.stats().count(ChangeEvent.Kind.SERVICE_CALL) == 1 ? "" : "s",
                r.outcome(), r.durationMillis(), r.stats().overheadNanos() / 1e6);
    }

    private static List<SessionState.Variable> variables(Frame frame, boolean global) {
        List<SessionState.Variable> vars = new ArrayList<>();
        if (frame.activation == null) return vars;
        for (Object id : frame.activation.getIds()) {
            String name = String.valueOf(id);
            if (name.equals("arguments") || (global && ScriptRunner.BUILT_INS.contains(name))) continue;
            Object value = id instanceof Integer index
                    ? ScriptableObject.getProperty(frame.activation, index)
                    : ScriptableObject.getProperty(frame.activation, name);
            vars.add(new SessionState.Variable(name, Values.type(value), Values.describe(value)));
        }
        return vars;
    }

    private static List<Integer> sorted(Set<Integer> lines) {
        return lines.stream().sorted().toList();
    }
}
