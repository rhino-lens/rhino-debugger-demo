package io.github.rhinolens.debugdemo.debug;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import io.github.rhinolens.debugdemo.debug.DebugSession.Action;
import io.github.rhinolens.debugdemo.debug.DebugSession.Status;
import io.github.rhinolens.lens.Lens;

class DebugSessionTest {

    // Lines of sample/rule.js
    private static final int LINE_TOTAL_HEADER = 4; // function lineTotal(item) {
    private static final int LINE_TOTAL_BODY = 5;  // var amount = item.getQuantity() * ...
    private static final int CALL_LINE = 13;       // var amount = lineTotal(item);
    private static final int PRINT_LINE = 14;
    private static final int ADD_LINE = 15;

    private final BlockingQueue<SessionState> states = new LinkedBlockingQueue<>();

    @BeforeAll
    static void installLens() {
        Lens.install();
    }

    @Test
    void lensRecordsWhatTheRuleChanged() throws Exception {
        start(sample(), List.of(), false);
        SessionState s = await(st -> st.status() == Status.FINISHED);

        assertThat(s.result()).isEqualTo("35.09");
        assertThat(s.changes()).extracting(SessionState.ChangeView::kind, SessionState.ChangeView::target, SessionState.ChangeView::line)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("SERVICE", "pricing.discountFor(\"GOLD\", 38.99)", 18),
                        org.assertj.core.groups.Tuple.tuple("SETTER", "order.finalTotal", 20),
                        org.assertj.core.groups.Tuple.tuple("ASSIGNMENT", "order.status", 22),
                        org.assertj.core.groups.Tuple.tuple("COLLECTION", "order.getNotes().add(\"priced by rule.js\")", 23),
                        org.assertj.core.groups.Tuple.tuple("SERVICE", "orderRepository.save(Order(id=1001))", 24),
                        org.assertj.core.groups.Tuple.tuple("MARK", "pricing done", 25));
        SessionState.ChangeView total = s.changes().stream().filter(c -> c.kind().equals("SETTER")).findFirst().orElseThrow();
        assertThat(total.detail()).isEqualTo("0.0 \u2192 35.09");
        assertThat(s.lensSummary()).startsWith("3 changes").contains("SUCCESS");
    }

    @Test
    void breakpointsStepsEvaluateAndFinish() throws Exception {
        DebugSession session = start(sample(), List.of(CALL_LINE), false);

        SessionState s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.pauseReason()).isEqualTo("breakpoint");
        assertThat(s.line()).isEqualTo(CALL_LINE);
        assertThat(s.stack()).hasSize(1);
        assertThat(var(s, 0, "i")).isEqualTo("0");
        assertThat(type(s, 0, "item")).isEqualTo("Java LineItem");
        assertThat(type(s, 0, "order")).isEqualTo("Java Order");
        assertThat(s.breakableLines()).contains(LINE_TOTAL_BODY, CALL_LINE, PRINT_LINE);

        // Rhino reports the function's own line first when it enters a function.
        send(session, Action.STEP_INTO);
        s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.line()).isEqualTo(LINE_TOTAL_HEADER);
        assertThat(s.stack()).extracting(SessionState.StackFrameView::name).containsExactly("lineTotal()", "(script)");
        assertThat(type(s, 0, "item")).isEqualTo("Java LineItem");

        send(session, Action.STEP_OVER);
        s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.line()).isEqualTo(LINE_TOTAL_BODY);
        assertThat(s.stack()).hasSize(2);

        send(session, Action.STEP_OUT);
        s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.stack()).hasSize(1);
        assertThat(s.line()).isEqualTo(PRINT_LINE);
        assertThat(var(s, 0, "amount")).isEqualTo("25");

        assertThat(session.evaluate("amount * 2", 0)).isEqualTo("50");
        assertThat(session.evaluate("item.getSku()", 0)).isEqualTo("\"MUG-RED\"");
        assertThat(session.evaluate("nope(", 0)).startsWith("error:");

        send(session, Action.STEP_OVER);
        s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.line()).isEqualTo(ADD_LINE);

        send(session, Action.CONTINUE);
        s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.line()).isEqualTo(CALL_LINE);
        assertThat(var(s, 0, "i")).isEqualTo("1");

        session.setBreakpoints(List.of());
        send(session, Action.CONTINUE);
        s = await(st -> st.status() == Status.FINISHED);
        assertThat(s.result()).isEqualTo("35.09");
        assertThat(s.console()).containsExactly(
                "line MUG-RED: 25", "line TEA-200G: 8.99", "line SPOON: 5", "final total: 35.09");
    }

    @Test
    void stopWhilePaused() throws Exception {
        DebugSession session = start(sample(), List.of(), true);
        SessionState s = await(st -> st.status() == Status.PAUSED);
        assertThat(s.pauseReason()).isEqualTo("step");
        send(session, Action.STOP);
        await(st -> st.status() == Status.STOPPED);
    }

    @Test
    void stopCannotBeCaughtByTheScript() throws Exception {
        DebugSession session = start("var n = 0;\nwhile (true) {\n  try { n++; } catch (e) { }\n}\n", List.of(), false);
        Thread.sleep(200);
        send(session, Action.STOP);
        await(st -> st.status() == Status.STOPPED);
    }

    @Test
    void scriptErrorsAreReported() throws Exception {
        start("var a = 1;\nundefinedFunction();\n", List.of(), false);
        SessionState s = await(st -> st.status() == Status.FAILED);
        assertThat(s.error()).contains("undefinedFunction").contains("line 2");
    }

    private void send(DebugSession session, Action action) {
        states.clear();
        assertThat(session.command(action)).isTrue();
    }

    private DebugSession start(String source, List<Integer> breakpoints, boolean pauseOnStart) {
        DebugSession session = new DebugSession("test", source, "rule.js", SessionRegistry.javaObjects(),
                breakpoints, pauseOnStart);
        session.addListener(states::add);
        Thread thread = new Thread(session::run, "script-test");
        thread.setDaemon(true);
        thread.start();
        return session;
    }

    private SessionState await(Predicate<SessionState> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            SessionState s = states.poll(100, TimeUnit.MILLISECONDS);
            if (s != null && condition.test(s)) return s;
        }
        throw new AssertionError("timed out waiting for state");
    }

    private static String var(SessionState s, int frame, String name) {
        return find(s, frame, name).value();
    }

    private static String type(SessionState s, int frame, String name) {
        return find(s, frame, name).type();
    }

    private static SessionState.Variable find(SessionState s, int frame, String name) {
        return s.stack().get(frame).variables().stream().filter(v -> v.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no variable " + name + " in " + s.stack().get(frame)));
    }

    private static String sample() throws IOException {
        return new ClassPathResource("sample/rule.js").getContentAsString(StandardCharsets.UTF_8);
    }
}
