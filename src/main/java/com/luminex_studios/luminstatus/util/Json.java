package com.luminex_studios.luminstatus.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately tiny JSON writer and reader.
 *
 * <p>LuminStatus emits a few kilobytes of JSON and reads back its own state
 * files. Pulling in a full serialisation library for that would add a shaded
 * dependency and a relocation problem for no benefit, so the format is
 * implemented here. The parser accepts standard JSON; it is only ever pointed at
 * files this plugin wrote.
 */
public final class Json {

    private Json() {
    }

    /** Ordered JSON object. Keys keep insertion order so diffs stay readable. */
    public static final class Obj {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Obj put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public Object get(String key) {
            return values.get(key);
        }

        public String string(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String s ? s : fallback;
        }

        public long number(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Number n ? n.longValue() : fallback;
        }

        public double decimal(String key, double fallback) {
            Object value = values.get(key);
            return value instanceof Number n ? n.doubleValue() : fallback;
        }

        public boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean b ? b : fallback;
        }

        public Obj obj(String key) {
            Object value = values.get(key);
            return value instanceof Obj o ? o : null;
        }

        public Arr arr(String key) {
            Object value = values.get(key);
            return value instanceof Arr a ? a : null;
        }

        public Collection<String> keys() {
            return List.copyOf(values.keySet());
        }
    }

    /** Ordered JSON array. */
    public static final class Arr {
        private final List<Object> values = new ArrayList<>();

        public Arr add(Object value) {
            values.add(value);
            return this;
        }

        public int size() {
            return values.size();
        }

        public Object get(int index) {
            return values.get(index);
        }

        public List<Object> values() {
            return List.copyOf(values);
        }
    }

    public static Obj obj() {
        return new Obj();
    }

    public static Arr arr() {
        return new Arr();
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(1024);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Obj obj) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : obj.values.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, entry.getKey());
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Arr arr) {
            out.append('[');
            for (int i = 0; i < arr.values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(out, arr.values.get(i));
            }
            out.append(']');
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else {
                out.append(d);
            }
        } else if (value instanceof Number n) {
            out.append(n.longValue());
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Parses a JSON document. Returns {@code null} for malformed input. */
    public static Object read(String input) {
        try {
            Parser parser = new Parser(input);
            Object value = parser.value();
            parser.skipWhitespace();
            return parser.done() ? value : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean done() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            skipWhitespace();
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String text, Object result) {
            if (!src.startsWith(text, pos)) {
                throw new IllegalStateException("Bad literal at " + pos);
            }
            pos += text.length();
            return result;
        }

        private Obj object() {
            Obj obj = new Obj();
            pos++; // '{'
            skipWhitespace();
            if (src.charAt(pos) == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                obj.put(key, value());
                skipWhitespace();
                char c = src.charAt(pos++);
                if (c == '}') {
                    return obj;
                }
                if (c != ',') {
                    throw new IllegalStateException("Expected , or } at " + pos);
                }
            }
        }

        private Arr array() {
            Arr arr = new Arr();
            pos++; // '['
            skipWhitespace();
            if (src.charAt(pos) == ']') {
                pos++;
                return arr;
            }
            while (true) {
                arr.add(value());
                skipWhitespace();
                char c = src.charAt(pos++);
                if (c == ']') {
                    return arr;
                }
                if (c != ',') {
                    throw new IllegalStateException("Expected , or ] at " + pos);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalStateException("Bad escape at " + pos);
                }
            }
        }

        private Number number() {
            int start = pos;
            while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String text = src.substring(start, pos);
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        }

        private void expect(char expected) {
            if (src.charAt(pos++) != expected) {
                throw new IllegalStateException("Expected " + expected + " at " + (pos - 1));
            }
        }
    }
}
