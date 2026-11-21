package io.github.rhinolens.debugdemo.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

/**
 * Renders script values for the variables view without running script code:
 * JS objects are read property by property, Java objects through their own toString().
 */
final class Values {

    private static final int MAX_TEXT = 200;
    private static final int MAX_ITEMS = 10;

    private Values() {
    }

    static String type(Object v) {
        if (v == null) return "null";
        if (v == Undefined.instance) return "undefined";
        if (v instanceof Wrapper w) {
            Object o = w.unwrap();
            return o == null ? "Java null" : "Java " + o.getClass().getSimpleName();
        }
        if (v instanceof CharSequence) return "string";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Function) return "function";
        if (v instanceof NativeArray) return "array";
        if (v instanceof Scriptable) return "object";
        return v.getClass().getSimpleName();
    }

    static String describe(Object v) {
        return describe(v, 0);
    }

    private static String describe(Object v, int depth) {
        if (v == null) return "null";
        if (v == Undefined.instance) return "undefined";
        if (v instanceof Wrapper w) {
            Object o = w.unwrap();
            if (o instanceof CharSequence s) return quote(s);
            if (o instanceof Object[] array) return cut(Arrays.toString(array));
            return cut(String.valueOf(o));
        }
        if (v instanceof CharSequence s) return quote(s);
        if (v instanceof Number n) return ScriptRuntime.numberToString(n.doubleValue(), 10);
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Function) {
            String name = v instanceof BaseFunction f ? f.getFunctionName() : "";
            return "function " + (name == null ? "" : name) + "()";
        }
        if (v instanceof NativeArray a) {
            if (depth > 0) return "[…]";
            List<String> parts = new ArrayList<>();
            long n = a.getLength();
            for (int i = 0; i < Math.min(n, MAX_ITEMS); i++) parts.add(describe(a.get(i, a), depth + 1));
            if (n > MAX_ITEMS) parts.add("… " + (n - MAX_ITEMS) + " more");
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Scriptable s) {
            if (depth > 0) return "{…}";
            List<String> parts = new ArrayList<>();
            Object[] ids = s.getIds();
            for (int i = 0; i < Math.min(ids.length, MAX_ITEMS); i++) {
                Object id = ids[i];
                Object value = id instanceof Integer index
                        ? ScriptableObject.getProperty(s, index)
                        : ScriptableObject.getProperty(s, String.valueOf(id));
                parts.add(id + ": " + describe(value, depth + 1));
            }
            if (ids.length > MAX_ITEMS) parts.add("… " + (ids.length - MAX_ITEMS) + " more");
            return "{" + String.join(", ", parts) + "}";
        }
        return cut(String.valueOf(v));
    }

    private static String quote(CharSequence s) {
        return '"' + cut(s.toString()).replace("\n", "\\n") + '"';
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }
}
