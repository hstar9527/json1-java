package com.ottypes.json1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilities for representing JSON documents (and JSON1 operations) in Java.
 *
 * <p>A document is represented with plain java objects:
 * {@code null}, {@link Boolean}, {@link Number}, {@link String},
 * {@code List<Object>} and {@code Map<String, Object>} (always a LinkedHashMap so
 * key order is stable).
 *
 * <p>The javascript implementation distinguishes {@code undefined} from {@code null}.
 * {@code undefined} is modelled here with the {@link #UNDEFINED} sentinel object. Any
 * java method which returns "Doc or undefined" returns either a document value or that
 * sentinel - never java {@code null} unless the document value really is JSON null.
 */
public final class Json {

    /**
     * Sentinel standing in for javascript's {@code undefined}.
     */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private Json() {
    }

    // ------------------------------------------------------------------ types

    public static boolean isList(Object o) {
        return o instanceof List;
    }

    public static boolean isMap(Object o) {
        return o instanceof Map;
    }

    /**
     * true if o is a non-null non-list object (mirror of `typeof o === 'object' &amp;&amp; !Array.isArray(o)`).
     */
    public static boolean isObj(Object o) {
        return o instanceof Map;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * An op list: every element is a key (Integer/String), a component or a child list.
     */
    public static boolean isOpList(Object o) {
        return o instanceof List;
    }

    // -------------------------------------------------------------- cloning

    public static Object shallowClone(Object obj) {
        if (obj instanceof List) return new ArrayList<>(asList(obj));
        if (obj instanceof Map) return new LinkedHashMap<>(asMap(obj));
        return obj;
    }

    public static Object deepClone(Object old) {
        if (old == null) return null;
        if (old instanceof OpComponent) {
            // Operation components are always cloned into new components; their identity
            // (not their content) is meaningful in a few places in this library.
            OpComponent src = (OpComponent) old;
            OpComponent out = new OpComponent();
            for (Map.Entry<String, Object> e : src.entrySet()) out.put(e.getKey(), deepClone(e.getValue()));
            return out;
        }
        if (old instanceof List) {
            List<Object> src = asList(old);
            List<Object> out = new ArrayList<>(src.size());
            for (Object o : src) out.add(deepClone(o));
            return out;
        }
        if (old instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : asMap(old).entrySet()) out.put(e.getKey(), deepClone(e.getValue()));
            return out;
        }
        return old;
    }

    // ----------------------------------------------------------- comparisons

    /**
     * Structural equality. Numbers compare numerically so {@code 5} and {@code 5.0}
     * are equal, matching javascript semantics.
     */
    public static boolean deepEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof CharSequence && b instanceof CharSequence) {
            return a.toString().equals(b.toString());
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof List) {
            if (!(b instanceof List)) return false;
            List<Object> la = asList(a), lb = asList(b);
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!deepEqual(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map) {
            if (!(b instanceof Map)) return false;
            Map<String, Object> ma = asMap(a), mb = asMap(b);
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<String, Object> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!deepEqual(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Mirrors javascript's {@code ===} for document values: primitives compare by value,
     * containers by identity. Used by apply() to work out whether a subtree was modified.
     */
    public static boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if ((a instanceof CharSequence && b instanceof CharSequence)
                || (a instanceof Boolean && b instanceof Boolean)) {
            return a.equals(b);
        }
        return false; // objects compare by reference
    }

    // ------------------------------------------------------------ key order

    /**
     * Numbers sort before strings; otherwise children are visited in ascending order.
     * The javascript version is {@code typeof a === typeof b ? a > b : typeof a === 'string'}.
     */
    public static boolean isGreaterKey(Object a, Object b) {
        boolean aNum = a instanceof Integer, bNum = b instanceof Integer;
        if (aNum == bNum) return compareKeys(a, b) > 0;
        // Numbers sort before strings. Only "string > number" is true here.
        return !aNum && bNum;
    }

    public static int compareKeys(Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            return Integer.compare((Integer) a, (Integer) b);
        }
        return ((String) a).compareTo((String) b);
    }

    public static int asIndex(Object key) {
        return ((Integer) key).intValue();
    }

    // ------------------------------------------------------ child accessors

    /**
     * true if container can be indexed by the given key.
     */
    public static boolean isValidKey(Object container, Object key) {
        if (container == null) return false;
        if (key instanceof Integer) return container instanceof List;
        return container instanceof Map;
    }

    /**
     * Raw (unchecked) read of container[key]. Returns UNDEFINED if the child is missing.
     */
    public static Object getRaw(Object container, Object key) {
        if (key instanceof Integer) {
            List<Object> l = asList(container);
            int i = (Integer) key;
            return i < l.size() ? l.get(i) : UNDEFINED;
        }
        String k = (String) key;
        Map<String, Object> m = asMap(container);
        return m.containsKey(k) ? m.get(k) : UNDEFINED;
    }

    public static Object setRaw(Object container, Object key, Object value) {
        if (key instanceof Integer) asList(container).set((Integer) key, value);
        else asMap(container).put((String) key, value);
        return value;
    }

    /**
     * Container child access which never throws - returns UNDEFINED if there's no such child.
     */
    public static Object maybeGetChild(Object container, Object key) {
        return isValidKey(container, key) ? getRaw(container, key) : UNDEFINED;
    }

    /**
     * Remove a key from a container. Returns a (shallow clonecontainer with the key removed.
     */
    public static Object removeChild(Object container, Object key) {
        if (key instanceof Integer) {
            List<Object> l = new ArrayList<>(asList(container));
            l.remove((int) (Integer) key);
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>(asMap(container));
        m.remove((String) key);
        return m;
    }

    /**
     * Insert value at key, mutating the container.  Returns the inserted value.
     */
    public static Object insertChildMut(Object container, Object key, Object value) {
        if (key instanceof Integer) {
            List<Object> l = asList(container);
            int i = (Integer) key;
            if (i > l.size()) throw new AssertionError("Cannot insert into out of bounds index");
            l.add(i, value);
        } else {
            Map<String, Object> m = asMap(container);
            if (m.containsKey((String) key)) {
                throw new AssertionError("Trying to overwrite value at key. Your op needs to remove it first");
            }
            m.put((String) key, value);
        }
        return value;
    }

    /**
     * Replace (never insert) child key with value. Returns a shallow clone of the container.
     */
    public static Object replaceChild(Object container, Object key, Object value) {
        Object clone = shallowClone(container);
        setRaw(clone, key, value);
        return clone;
    }

    // -------------------------------------------------------------- parsing

    /**
     * Parse JSON text into the internal representation.
     */
    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i < p.s.length()) throw new IllegalArgumentException("Trailing garbage at " + p.i);
        return v;
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof OpComponent || o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : asMap(o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : asList(o)) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) {
            sb.append(o.toString());
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == (long) d && !Double.isInfinite(d)) sb.append((long) d);
            else sb.append(d);
        } else {
            escape(sb, o.toString());
        }
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected " + c + " at " + i);
            i++;
        }

        Object value() {
            skipWs();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                case '\'':
                    return string();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    return number();
            }
        }

        void expectWord(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("Bad literal at " + i);
            i += w.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String k = objectKey();
                skipWs();
                expect(':');
                m.put(k, value());
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at " + (i - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            expect('[');
            skipWs();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + (i - 1));
            }
        }

        /**
         * Object keys may be quoted or bare identifiers, javascript object-literal style.
         */
        String objectKey() {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') return string();
            int start = i;
            while (i < s.length()
                    && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) {
                i++;
            }
            if (i == start) throw new IllegalArgumentException("Expected object key at " + i);
            return s.substring(start, i);
        }

        /**
         * Accepts both double- and single-quoted strings, which makes test fixtures readable.
         */
        String string() {
            char quote = s.charAt(i++);
            if (quote != '"' && quote != '\'') throw new IllegalArgumentException("Expected a string at " + (i - 1));
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == quote) return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        sb.append(e);
                }
            }
        }

        Object number() {
            int start = i;
            boolean fractional = false;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) {
                if (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E') fractional = true;
                i++;
            }
            String tok = s.substring(start, i);
            if (!fractional) {
                try {
                    return Integer.valueOf(Integer.parseInt(tok));
                } catch (NumberFormatException e) { /* fall through */ }
                try {
                    return Long.valueOf(Long.parseLong(tok));
                } catch (NumberFormatException e) { /* fall through */ }
            }
            return Double.valueOf(Double.parseDouble(tok));
        }
    }
}
