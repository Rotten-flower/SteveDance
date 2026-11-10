package dev.flower.stevedance.planet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 编译恢复代码使用的 Java 值操作。无脚本解析器或解释器。
 * <p>Java value operations used by compiled recovered code. No script parser or interpreter.</p>
 */
public final class Values {
    private Values() {}

    static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    static final class Context {
        final Map<String, Scope> modules = new LinkedHashMap<>();
        final Map<String, Object> data;
        final Scope builtins = new Scope(null, null);

        Context(Map<String, Object> data) {
            this.data = data;
            installBuiltins(builtins);
        }
    }

    public static final class Scope {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Scope outer, global;

        Scope(Scope outer, Scope global) {
            this.outer = outer;
            this.global = global == null ? this : global;
        }

        Object get(String name) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
            if (outer != null) {
                return outer.get(name);
            }
            throw new IllegalStateException("Missing compiled binding: " + name);
        }

        void set(String name, Object value) {
            values.put(name, value);
        }

        void global(String name, Object value) {
            global.set(name, value);
        }
    }

    @FunctionalInterface
    interface Body {
        void run(Scope scope);
    }

    @FunctionalInterface
    interface Fn {
        Object apply(List<Object> args, Map<Object, Object> kwargs);
    }

    static final class Returned extends RuntimeException {
        final Object value;

        Returned(Object v) {
            super(null, null, false, false);
            value = v;
        }
    }

    static void ret(Object value) {
        throw new Returned(value);
    }

    static void discard(Object v) {}

    static void fail(String text) {
        throw new IllegalStateException(text);
    }

    static Object cached(Object function) {
        Map<List<Object>, Object> cache = new HashMap<>();
        return (Fn)
                (a, k) -> {
                    List<Object> key = new ArrayList<>(a);
                    if (cache.containsKey(key)) {
                        return cache.get(key);
                    }
                    Object value = call(function, a, k);
                    cache.put(key, value);
                    return value;
                };
    }

    static Object function(
            Scope closure,
            String name,
            String[] params,
            Object[] defaults,
            String vararg,
            String[] locals,
            Body body) {
        return (Fn)
                (args, kw) -> {
                    Scope scope = new Scope(closure, closure.global);
                    int required = params.length - defaults.length;
                    for (int i = 0; i < params.length; i++) {
                        Object value;
                        if (i < args.size()) {
                            value = args.get(i);
                        } else if (kw.containsKey(params[i])) {
                            value = kw.get(params[i]);
                        } else if (i >= required) {
                            value = defaults[i - required];
                        } else {
                            throw new IllegalArgumentException(name + " missing " + params[i]);
                        }
                        scope.set(params[i], value);
                    }
                    if (vararg != null) {
                        scope.set(
                                vararg,
                                new ArrayList<>(
                                        args.subList(
                                                Math.min(args.size(), params.length),
                                                args.size())));
                    } else if (args.size() > params.length) {
                        throw new IllegalArgumentException(name + " extra arguments");
                    }
                    try {
                        body.run(scope);
                        return null;
                    } catch (Returned r) {
                        return r.value;
                    } catch (RuntimeException x) {
                        throw new IllegalStateException(name + ": " + x.getMessage(), x);
                    }
                };
    }

    static final class Kind {
        final String name, module, base;
        final Map<String, Object> methods = new LinkedHashMap<>();

        Kind(String n, String m, String b) {
            name = n;
            module = m;
            base = b;
        }

        Kind parent() {
            return base == null ? null : (Kind) module(module).get(base);
        }

        Object method(String name) {
            if (methods.containsKey(name)) {
                return methods.get(name);
            }
            Kind p = parent();
            if (p != null) {
                return p.method(name);
            }
            throw new IllegalArgumentException(this.name + " missing method " + name);
        }
    }

    static final class Instance {
        final Kind kind;
        final Map<String, Object> fields = new LinkedHashMap<>();

        Instance(Kind k) {
            kind = k;
        }
    }

    record Parent(Instance instance, Kind kind) {}

    static Object parent(Scope scope, String owner) {
        Instance self = (Instance) scope.get("self");
        Kind k = (Kind) scope.global.get(owner);
        return new Parent(self, k.parent());
    }

    static Object bind(Object f, Object self) {
        return (Fn)
                (args, kw) -> {
                    List<Object> all = new ArrayList<>();
                    all.add(self);
                    all.addAll(args);
                    return call(f, all, kw);
                };
    }

    @SuppressWarnings("unchecked")
    static Object call(Object f, Object args, Object kwargs) {
        if (f instanceof Fn fn) {
            return fn.apply((List<Object>) args, (Map<Object, Object>) kwargs);
        }
        if (f instanceof Kind k) {
            Instance self = new Instance(k);
            call(bind(k.method("__init__"), self), args, kwargs);
            return self;
        }
        throw new IllegalArgumentException("Not callable: " + f);
    }

    static Scope module(String name) {
        Scope s = CURRENT.get().modules.get(name);
        if (s == null) {
            throw new IllegalArgumentException("Missing module: " + name);
        }
        return s;
    }

    static Object collect(Scope scope, Function<Scope, Object> body) {
        return body.apply(new Scope(scope, scope.global));
    }

    static Object lazy(String op, Object a, Supplier<Object> b) {
        return truth(a) == op.equals("And") ? b.get() : a;
    }

    static boolean truth(Object a) {
        if (a == null) {
            return false;
        }
        if (a instanceof Boolean b) {
            return b;
        }
        if (a instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (a instanceof String s) {
            return !s.isEmpty();
        }
        if (a instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (a instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static double number(Object a) {
        if (a instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) a).doubleValue();
    }

    static long integer(Object a) {
        return (long) number(a);
    }

    static boolean integral(Object a) {
        return a instanceof Boolean
                || a instanceof Byte
                || a instanceof Short
                || a instanceof Integer
                || a instanceof Long;
    }

    static Object key(Object a) {
        if (a instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (a instanceof Number n && n.doubleValue() == n.longValue()) {
            return n.longValue();
        }
        return a;
    }

    static String str(Object a) {
        if (a == null) {
            return "None";
        }
        if (a instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return String.valueOf(a);
    }

    static boolean equal(Object a, Object b) {
        if ((a instanceof Number || a instanceof Boolean)
                && (b instanceof Number || b instanceof Boolean)) {
            return number(a) == number(b);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!equal(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    static boolean compare(String op, Object a, Object b) {
        return switch (op) {
            case "Eq" -> equal(a, b);
            case "NotEq" -> !equal(a, b);
            case "Is" -> a == b;
            case "IsNot" -> a != b;
            case "Lt" -> number(a) < number(b);
            case "LtE" -> number(a) <= number(b);
            case "Gt" -> number(a) > number(b);
            case "GtE" -> number(a) >= number(b);
            case "In" -> contains(b, a);
            case "NotIn" -> !contains(b, a);
            default -> throw new IllegalArgumentException(op);
        };
    }

    static boolean contains(Object collection, Object value) {
        if (collection instanceof Map<?, ?> m) {
            return m.containsKey(key(value));
        }
        if (collection instanceof String s) {
            return s.contains(str(value));
        }
        for (Object x : iter(collection)) {
            if (equal(x, value)) {
                return true;
            }
        }
        return false;
    }

    static Object unary(String op, Object a) {
        return switch (op) {
            case "Not" -> !truth(a);
            case "USub" -> integral(a) ? (Object) (-integer(a)) : -number(a);
            case "UAdd" -> a;
            default -> throw new IllegalArgumentException(op);
        };
    }

    static Object op(String op, Object a, Object b) {
        if (op.equals("Add")) {
            if (a instanceof String || b instanceof String) {
                return str(a) + str(b);
            }
            if (a instanceof List<?> && b instanceof List<?>) {
                return concat(a, b);
            }
        }
        if (op.equals("Mult") && a instanceof List<?>) {
            List<Object> out = list();
            for (int i = 0; i < integer(b); i++) {
                out.addAll(iter(a));
            }
            return out;
        }
        if (op.equals("Mult") && a instanceof String s) {
            return s.repeat((int) integer(b));
        }
        if (op.equals("BitOr") && a instanceof Collection<?>) {
            List<Object> out = concat(a, b);
            return new ArrayList<>(new LinkedHashSet<>(out));
        }
        boolean ints = integral(a) && integral(b);
        long x = integral(a) ? integer(a) : 0, y = integral(b) ? integer(b) : 0;
        double d = number(a), e = number(b);
        return switch (op) {
            case "Add" -> ints ? (Object) (x + y) : d + e;
            case "Sub" -> ints ? (Object) (x - y) : d - e;
            case "Mult" -> ints ? (Object) (x * y) : d * e;
            case "Div" -> d / e;
            case "FloorDiv" -> ints ? (Object) Math.floorDiv(x, y) : Math.floor(d / e);
            case "Mod" -> ints ? (Object) Math.floorMod(x, y) : d - Math.floor(d / e) * e;
            case "Pow" -> ints ? (Object) (long) Math.pow(d, e) : Math.pow(d, e);
            case "BitAnd" -> integer(a) & integer(b);
            case "BitOr" -> integer(a) | integer(b);
            case "BitXor" -> integer(a) ^ integer(b);
            case "LShift" -> integer(a) << integer(b);
            case "RShift" -> integer(a) >> integer(b);
            default -> throw new IllegalArgumentException(op);
        };
    }

    static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    static Map<Object, Object> dict(Object... values) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            m.put(key(values[i]), values[i + 1]);
        }
        return m;
    }

    static List<Object> concat(Object a, Object b) {
        List<Object> out = new ArrayList<>(iter(a));
        out.addAll(iter(b));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> merge(Object a, Object b) {
        ((Map<Object, Object>) a).putAll((Map<Object, Object>) b);
        return (Map<Object, Object>) a;
    }

    @SuppressWarnings("unchecked")
    static List<Object> iter(Object a) {
        if (a instanceof List<?>) {
            return (List<Object>) a;
        }
        if (a instanceof Map<?, ?> m) {
            return new ArrayList<>(m.keySet());
        }
        if (a instanceof byte[] b) {
            List<Object> out = list();
            for (byte v : b) {
                out.add((long) (v & 255));
            }
            return out;
        }
        if (a instanceof String s) {
            List<Object> out = list();
            for (int i = 0; i < s.length(); i++) {
                out.add(s.substring(i, i + 1));
            }
            return out;
        }
        throw new IllegalArgumentException("Not iterable: " + a);
    }

    static int index(Object value, int length) {
        int i = (int) integer(value);
        return i < 0 ? i + length : i;
    }

    static Object item(Object container, Object at) {
        if (container instanceof Map<?, ?> m) {
            Object k = key(at);
            if (!m.containsKey(k)) {
                throw new IllegalArgumentException("Missing field: " + k);
            }
            return m.get(k);
        }
        if (container instanceof byte[] b) {
            return (long) (b[index(at, b.length)] & 255);
        }
        if (container instanceof String s) {
            return s.substring(index(at, s.length()), index(at, s.length()) + 1);
        }
        List<Object> l = iter(container);
        return l.get(index(at, l.size()));
    }

    @SuppressWarnings("unchecked")
    static void put(Object container, Object at, Object value) {
        if (container instanceof Map<?, ?>) {
            ((Map<Object, Object>) container).put(key(at), value);
        } else {
            List<Object> l = (List<Object>) container;
            l.set(index(at, l.size()), value);
        }
    }

    static int bound(Object v, int n, int fallback) {
        return v == null ? fallback : Math.max(0, Math.min(n, index(v, n)));
    }

    static Object slice(Object a, Object start, Object stop, Object step) {
        List<Object> source = iter(a);
        int stride = step == null ? 1 : (int) integer(step), n = source.size();
        List<Object> out = list();
        int i = bound(start, n, stride > 0 ? 0 : n - 1), end = bound(stop, n, stride > 0 ? n : -1);
        for (; stride > 0 ? i < end : i > end; i += stride) {
            out.add(source.get(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void putSlice(Object a, Object start, Object stop, Object value) {
        List<Object> l = (List<Object>) a;
        int from = bound(start, l.size(), 0), to = bound(stop, l.size(), l.size());
        l.subList(from, to).clear();
        l.addAll(from, iter(value));
    }

    @SuppressWarnings("unchecked")
    static void append(Object a, Object value) {
        ((List<Object>) a).add(value);
    }

    static void setattr(Object o, String name, Object v) {
        ((Instance) o).fields.put(name, v);
    }

    static Object attr(Object o, String name) {
        if (o instanceof Instance i) {
            if (i.fields.containsKey(name)) {
                return i.fields.get(name);
            }
            return bind(i.kind.method(name), i);
        }
        if (o instanceof Parent p) {
            return bind(p.kind.method(name), p.instance);
        }
        if (o instanceof Map<?, ?> m) {
            if (m.containsKey(name) && m.get(name) instanceof Fn) {
                return m.get(name);
            }
            return (Fn)
                    (a, k) ->
                            switch (name) {
                                case "get" ->
                                        m.containsKey(key(a.get(0)))
                                                ? m.get(key(a.get(0)))
                                                : a.size() > 1 ? a.get(1) : null;
                                case "items" -> {
                                    List<Object> out = list();
                                    m.forEach((key, value) -> out.add(list(key, value)));
                                    yield out;
                                }
                                case "copy" -> new LinkedHashMap<>(m);
                                case "update" -> {
                                    merge(o, a.get(0));
                                    yield null;
                                }
                                case "clear" -> {
                                    m.clear();
                                    yield null;
                                }
                                default ->
                                        throw new IllegalArgumentException("Map method: " + name);
                            };
        }
        if (o instanceof List<?> l) {
            return (Fn)
                    (a, k) ->
                            switch (name) {
                                case "append" -> {
                                    append(l, a.get(0));
                                    yield null;
                                }
                                case "extend" -> {
                                    for (Object v : iter(a.get(0))) {
                                        append(l, v);
                                    }
                                    yield null;
                                }
                                case "clear" -> {
                                    l.clear();
                                    yield null;
                                }
                                case "copy" -> new ArrayList<>(l);
                                default ->
                                        throw new IllegalArgumentException("List method: " + name);
                            };
        }
        if (o instanceof String s) {
            return (Fn)
                    (a, k) ->
                            switch (name) {
                                case "split" ->
                                        list(
                                                (Object[])
                                                        s.split(
                                                                java.util.regex.Pattern.quote(
                                                                        str(a.get(0))),
                                                                -1));
                                case "replace" -> s.replace(str(a.get(0)), str(a.get(1)));
                                case "startswith" -> s.startsWith(str(a.get(0)));
                                case "removeprefix" ->
                                        s.startsWith(str(a.get(0)))
                                                ? s.substring(str(a.get(0)).length())
                                                : s;
                                case "rstrip" -> s.stripTrailing();
                                case "join" ->
                                        String.join(
                                                s,
                                                iter(a.get(0)).stream().map(Values::str).toList());
                                default ->
                                        throw new IllegalArgumentException(
                                                "String method: " + name);
                            };
        }
        if (o instanceof Path p) {
            return (Fn)
                    (a, k) -> {
                        try {
                            return switch (name) {
                                case "exists" -> Files.exists(p);
                                case "read_text" -> Files.readString(p, StandardCharsets.UTF_8);
                                case "write_text" -> {
                                    Files.createDirectories(p.toAbsolutePath().getParent());
                                    Files.writeString(p, str(a.get(0)), StandardCharsets.UTF_8);
                                    yield null;
                                }
                                default -> throw new IllegalArgumentException(name);
                            };
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    };
        }
        if (o instanceof Random r) {
            return (Fn)
                    (a, k) ->
                            switch (name) {
                                case "sample" -> {
                                    List<Object> all = new ArrayList<>(iter(a.get(0)));
                                    Collections.shuffle(all, r);
                                    yield new ArrayList<>(all.subList(0, (int) integer(a.get(1))));
                                }
                                case "randrange" -> (long) r.nextInt((int) integer(a.get(0)));
                                default -> throw new IllegalArgumentException(name);
                            };
        }
        throw new IllegalArgumentException("Attribute " + name + " on " + o);
    }

    static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static Object unpack(List<Object> a) {
        String f = str(a.get(0)).substring(1);
        byte[] bytes = (byte[]) a.get(1);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (a.size() > 2) {
            b.position((int) integer(a.get(2)));
        }
        List<Object> out = list();
        String digits = "";
        for (char c : f.toCharArray()) {
            if (Character.isDigit(c)) {
                digits += c;
                continue;
            }
            int count = digits.isEmpty() ? 1 : Integer.parseInt(digits);
            digits = "";
            for (int i = 0; i < count; i++) {
                out.add(
                        switch (c) {
                            case 'f' -> (double) b.getFloat();
                            case 'i' -> (long) b.getInt();
                            case 'I' -> Integer.toUnsignedLong(b.getInt());
                            case 'h' -> (long) b.getShort();
                            default -> throw new IllegalArgumentException("struct " + c);
                        });
            }
        }
        return out;
    }

    static Object asset(String name) {
        return copy(CURRENT.get().data.get("asset:" + name));
    }

    static Object copy(Object value) {
        if (value instanceof List<?> l) {
            List<Object> r = list();
            for (Object v : l) {
                r.add(copy(v));
            }
            return r;
        }
        if (value instanceof Map<?, ?> m) {
            Map<Object, Object> r = dict();
            m.forEach((k, v) -> r.put(k, copy(v)));
            return r;
        }
        return value;
    }

    static void installBuiltins(Scope s) {
        s.set(
                "len",
                (Fn)
                        (a, k) ->
                                (long)
                                        (a.get(0) instanceof Map<?, ?> m
                                                ? m.size()
                                                : iter(a.get(0)).size()));
        s.set(
                "range",
                (Fn)
                        (a, k) -> {
                            long start = a.size() > 1 ? integer(a.get(0)) : 0,
                                    end = integer(a.get(a.size() > 1 ? 1 : 0)),
                                    step = a.size() > 2 ? integer(a.get(2)) : 1;
                            List<Object> r = list();
                            for (long i = start; step > 0 ? i < end : i > end; i += step) {
                                r.add(i);
                            }
                            return r;
                        });
        s.set(
                "enumerate",
                (Fn)
                        (a, k) -> {
                            List<Object> r = list(), l = iter(a.get(0));
                            for (int i = 0; i < l.size(); i++) {
                                r.add(list((long) i, l.get(i)));
                            }
                            return r;
                        });
        s.set(
                "zip",
                (Fn)
                        (a, k) -> {
                            List<Object> r = list();
                            int n = Integer.MAX_VALUE;
                            for (Object l : a) {
                                n = Math.min(n, iter(l).size());
                            }
                            for (int i = 0; i < n; i++) {
                                List<Object> row = list();
                                for (Object l : a) {
                                    row.add(iter(l).get(i));
                                }
                                r.add(row);
                            }
                            return r;
                        });
        s.set(
                "reversed",
                (Fn)
                        (a, k) -> {
                            List<Object> r = new ArrayList<>(iter(a.get(0)));
                            Collections.reverse(r);
                            return r;
                        });
        s.set("list", (Fn) (a, k) -> a.isEmpty() ? list() : new ArrayList<>(iter(a.get(0))));
        s.set(
                "dict",
                (Fn)
                        (a, k) -> {
                            Map<Object, Object> r = dict();
                            if (!a.isEmpty()) {
                                if (a.get(0) instanceof Map<?, ?>) {
                                    merge(r, a.get(0));
                                } else {
                                    for (Object row : iter(a.get(0))) {
                                        r.put(key(item(row, 0L)), item(row, 1L));
                                    }
                                }
                            }
                            return merge(r, k);
                        });
        s.set(
                "int",
                (Fn)
                        (a, k) -> {
                            Object v = a.get(0);
                            return v instanceof String st
                                    ? Long.parseLong(
                                            st, a.size() > 1 ? (int) integer(a.get(1)) : 10)
                                    : integer(v);
                        });
        s.set("float", (Fn) (a, k) -> number(a.get(0)));
        s.set("str", (Fn) (a, k) -> str(a.get(0)));
        s.set("hex", (Fn) (a, k) -> "0x" + Long.toHexString(integer(a.get(0))));
        s.set(
                "round",
                (Fn)
                        (a, k) -> {
                            double mul = a.size() > 1 ? Math.pow(10, number(a.get(1))) : 1;
                            return Math.rint(number(a.get(0)) * mul) / mul;
                        });
        s.set(
                "min",
                (Fn)
                        (a, k) -> {
                            List<Object> l = a.size() == 1 ? iter(a.get(0)) : a;
                            Object best = l.get(0);
                            for (Object v : l) {
                                if (number(v) < number(best)) {
                                    best = v;
                                }
                            }
                            return best;
                        });
        s.set(
                "max",
                (Fn)
                        (a, k) -> {
                            List<Object> l = a.size() == 1 ? iter(a.get(0)) : a;
                            Object best = l.get(0);
                            for (Object v : l) {
                                if (number(v) > number(best)) {
                                    best = v;
                                }
                            }
                            return best;
                        });
        s.set(
                "sum",
                (Fn)
                        (a, k) -> {
                            Object sum = 0L;
                            for (Object v : iter(a.get(0))) {
                                sum = op("Add", sum, v);
                            }
                            return sum;
                        });
        s.set(
                "any",
                (Fn)
                        (a, k) -> {
                            for (Object v : iter(a.get(0))) {
                                if (truth(v)) {
                                    return true;
                                }
                            }
                            return false;
                        });
        s.set(
                "next",
                (Fn)
                        (a, k) -> {
                            List<Object> l = iter(a.get(0));
                            if (!l.isEmpty()) {
                                return l.get(0);
                            }
                            if (a.size() > 1) {
                                return a.get(1);
                            }
                            throw new NoSuchElementException();
                        });
        for (String name : List.of("IndexError", "ValueError", "NotImplementedError")) {
            s.set(name, (Fn) (a, k) -> str(a.get(0)));
        }
        s.set("bytes", dict("fromhex", (Fn) (a, k) -> bytes(str(a.get(0)))));
        s.set("struct", dict("unpack_from", (Fn) (a, k) -> unpack(a)));
        s.set(
                "json",
                dict(
                        "loads",
                        (Fn) (a, k) -> fromJson(JsonParser.parseString(str(a.get(0)))),
                        "dumps",
                        (Fn) (a, k) -> new Gson().toJson(toJson(a.get(0)))));
        s.set("random", dict("Random", (Fn) (a, k) -> new Random()));
    }

    static Object fromJson(JsonElement j) {
        if (j.isJsonNull()) {
            return null;
        }
        if (j.isJsonArray()) {
            List<Object> l = list();
            for (JsonElement v : j.getAsJsonArray()) {
                l.add(fromJson(v));
            }
            return l;
        }
        if (j.isJsonObject()) {
            Map<Object, Object> m = dict();
            j.getAsJsonObject().entrySet().forEach(e -> m.put(e.getKey(), fromJson(e.getValue())));
            if (m.containsKey("$bytes")) {
                return Base64.getDecoder().decode(str(m.get("$bytes")));
            }
            if (m.containsKey("$map")) {
                Map<Object, Object> r = dict();
                for (Object pair : iter(m.get("$map"))) {
                    r.put(key(item(pair, 0L)), item(pair, 1L));
                }
                return r;
            }
            return m;
        }
        JsonPrimitive p = j.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isString()) {
            return p.getAsString();
        }
        String v = p.getAsString();
        return v.contains(".") || v.contains("e") || v.contains("E")
                ? (Object) p.getAsDouble()
                : p.getAsLong();
    }

    static JsonElement toJson(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (v instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((key, value) -> o.add(str(key), toJson(value)));
            return o;
        }
        if (v instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object x : l) {
                a.add(toJson(x));
            }
            return a;
        }
        throw new IllegalArgumentException("Not serializable " + v);
    }
}
