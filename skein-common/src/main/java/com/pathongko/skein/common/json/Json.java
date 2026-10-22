package com.pathongko.skein.common.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tiny, dependency-free JSON reader/writer for Skein's own manifest format.
 *
 * <p>Values map to plain Java types: {@code Map<String,Object>} (object),
 * {@code List<Object>} (array), {@code String}, {@code Long} or {@code Double}
 * (number), {@code Boolean}, and {@code null}. Object keys are written in sorted
 * order so encoding is deterministic — important because a manifest/index is
 * hashed, and a stable byte representation makes those hashes reproducible.
 *
 * <p>This is intentionally small (covers exactly what the manifest needs). A
 * production build may swap a full library behind the manifest codec.
 */
public final class Json {

    private Json() {
    }

    // --- writing -------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Long
                || value instanceof Integer || value instanceof Double) {
            sb.append(value.toString());
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // --- reading -------------------------------------------------------------

    public static Object read(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing content at index " + parser.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at index " + (pos - 1));
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at index " + (pos - 1));
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("invalid number at index " + start);
            }
            return floating ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
        }

        private Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("invalid literal at index " + pos);
        }

        private Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("invalid literal at index " + pos);
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new JsonException("expected '" + c + "' but got '" + actual + "' at index " + (pos - 1));
            }
        }
    }
}
