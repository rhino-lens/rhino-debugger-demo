package io.github.rhinolens.debugdemo.debug;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.debug.Debugger;

/**
 * Runs one script on the current thread the usual Rhino way: enter a Context, build a scope,
 * put Java objects into it, evaluate. For debugging it runs in interpreted mode
 * (optimization level -1) with debug info on, since Rhino only calls a Debugger there.
 */
final class ScriptRunner {

    /** Names this runner adds to the scope; the variables view hides them. */
    static final Set<String> BUILT_INS = Set.of("print", "console");

    /** Classes scripts may not reach through Packages / java.* (this server runs posted scripts). */
    private static final List<String> DENIED = List.of(
            "java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Thread",
            "java.lang.ClassLoader", "java.lang.reflect.", "java.lang.invoke.", "java.io.", "java.nio.",
            "java.net.", "sun.", "jdk.", "org.springframework.");

    private static final ClassShutter SHUTTER =
            className -> DENIED.stream().noneMatch(className::startsWith);

    private ScriptRunner() {
    }

    /** Returns the script's completion value, rendered for display. */
    static String run(String source, String sourceName, Map<String, Object> javaObjects,
                      Debugger debugger, Consumer<String> console) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setGeneratingDebug(true);
            cx.setGeneratingSource(true);
            cx.setDebugger(debugger, null);
            cx.setClassShutter(SHUTTER);

            ScriptableObject scope = cx.initStandardObjects();
            javaObjects.forEach((name, object) ->
                    ScriptableObject.putProperty(scope, name, Context.javaToJS(object, scope)));

            Print print = new Print(console);
            ScriptRuntime.setFunctionProtoAndParent(print, scope);
            ScriptableObject.putProperty(scope, "print", print);
            Scriptable consoleObject = cx.newObject(scope);
            ScriptableObject.putProperty(consoleObject, "log", print);
            ScriptableObject.putProperty(scope, "console", consoleObject);

            Object result = cx.evaluateString(scope, source, sourceName, 1, null);
            return Values.describe(result);
        } finally {
            Context.exit();
        }
    }

    /** print(a, b, …) and console.log(…): joins the arguments with spaces. */
    private static final class Print extends BaseFunction {
        private final Consumer<String> console;

        Print(Consumer<String> console) {
            this.console = console;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) line.append(' ');
                line.append(Context.toString(args[i]));
            }
            console.accept(line.toString());
            return Undefined.instance;
        }

        @Override
        public String getFunctionName() {
            return "print";
        }
    }
}
