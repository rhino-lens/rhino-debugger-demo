package io.github.rhinolens.debugdemo.debug;

import java.util.List;

/** Everything the UI shows for one debug session, sent whole on every change. */
public record SessionState(
        String id,
        DebugSession.Status status,
        int pauseCount,
        String pauseReason,
        int line,
        List<StackFrameView> stack,
        List<Integer> breakableLines,
        List<Integer> breakpoints,
        List<String> console,
        String result,
        String error,
        List<ChangeView> changes,
        String lensSummary) {

    /** One thing rhino-lens recorded: a property change, collection change, service call or mark. */
    public record ChangeView(int line, String kind, String target, String detail) {
    }

    /** One call-stack entry, innermost first. */
    public record StackFrameView(String name, int line, List<Variable> variables) {
    }

    public record Variable(String name, String type, String value) {
    }
}
