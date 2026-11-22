# rhino-debugger-demo

A small browser IDE that debugs JavaScript line by line on **batik-js-lens-base 1.7** (Rhino 1.6R5),
built on Rhino's own `Debugger` / `DebugFrame` API. Spring Boot 4.0.8 (Spring Framework 7.0.9), Java 21.

Features: breakpoints (click the gutter), Continue (F8), Step over (F10), Step into (F11),
Step out (Shift+F11), Stop (Shift+F5), call stack with frame selection, variables per frame,
watch expressions evaluated in the selected frame, and `print` / `console.log` output.
Scripts get Java objects in scope (`order`, `pricing`, `orderRepository`) to show Java interop.

**Changes panel ([batik-js-lens](https://github.com/rhino-lens/batik-js-lens)).** Each debug session is one
Lens execution, so as you step, the panel lists what the rule did to Java objects: setter calls and
`=` assignments with old → new values, collection changes, and service calls with arguments, results
and timings, each on its exact line. Click a change to highlight its line. Watch expressions are
evaluated unobserved, so inspecting never shows up as a change.

## Run

The demo depends on `io.github.rhino-lens:batik-js-lens-base:1.7` (a byte-identical rebuild of
`org.apache.xmlgraphics:batik-js:1.7`) and `io.github.rhino-lens:batik-js-lens:0.1.0` (observability).
Both are on Maven Central, so Maven downloads them. Then:

```sh
mvn package
java -jar target/rhino-debugger-demo-0.1.0-SNAPSHOT.jar
```

Open http://127.0.0.1:8099 (add `?autorun` to start a session straight away).
The server runs scripts posted to it, so it listens on 127.0.0.1 only.

## How it works

| Piece | Role |
|---|---|
| `debug/ScriptRunner` | Enters a Context in interpreted mode (`setOptimizationLevel(-1)`, debug info on, as Rhino requires for a Debugger), puts the Java objects in scope, evaluates |
| `DebuggerDemoApplication` | Calls `Lens.install()` before Spring starts |
| `debug/DebugSession` | Implements `Debugger`; wraps each run in a `LensExecution` and puts its events in every state update; one `DebugFrame` per call keeps the call stack. `onLineChange` decides whether to pause (breakpoint, step into/over/out) and then blocks the script thread until a command arrives. Watches are evaluated on that paused thread, in the frame's activation scope. Stop throws a `java.lang.Error`, which script `try/catch` cannot catch |
| `web/DebugController` | REST commands plus Server-Sent Events that push the whole session state on every change |
| `debug/SessionRegistry` | Fresh Java objects per run; `Lens.label` / `Lens.registerService` give changes readable names |
| `static/index.html` | React 18 + CodeMirror 5 page (from CDNs; no build step), with the Changes panel |

Rhino reports a function's own line (`function f() {`) first when it enters a function, so
Step into stops there before the first statement.

Compiled mode (optimization level 0–9) does not call a Debugger, so line-by-line debugging
always runs the script interpreted, which is slower.
