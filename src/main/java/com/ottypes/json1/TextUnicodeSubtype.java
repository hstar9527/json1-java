package com.ottypes.json1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bundled plain-text subtype (a port of
 * <a href="https://github.com/ottypes/text-unicode">ot-text-unicode</a>).
 *
 * <p>An operation is a list of components which are read in order over the string:
 * <ul>
 * <li>a number: skip that many characters</li>
 * <li>a string: insert this text</li>
 * <li>{@code {d: n}} or {@code {d: "removed text"}}: delete characters</li>
 * </ul>
 *
 * <p>An operation can also be a bare string, which means "replace the whole document".
 *
 * <p>Skips and deletes at the end of an operation are optional - anything not mentioned
 * by an operation is left alone.
 */
public class TextUnicodeSubtype extends Subtype {

    public TextUnicodeSubtype() {
        name = "text-unicode";
        uri = "http://sharejs.org/types/text-unicode";
    }

    @Override
    public Object apply(Object doc, Object op) {
        if (op == null) return doc;
        if (op instanceof String) return op;
        String s = doc instanceof String ? (String) doc : "";
        // Everything is counted in unicode code points, so emoji (and friends) count as one.
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (Object c : asList(op)) {
            if (c instanceof Number) {
                int n = ((Number) c).intValue();
                if (idx + n > cps.length) {
                    throw new IllegalArgumentException("Cannot skip past end of string");
                }
                for (int i = 0; i < n; i++) sb.appendCodePoint(cps[idx + i]);
                idx += n;
            } else if (c instanceof String) {
                sb.append((String) c);
            } else {
                int n = deleteLen(c);
                if (idx + n > cps.length) {
                    throw new IllegalArgumentException("Cannot delete past end of string");
                }
                idx += n;
            }
        }
        for (int i = idx; i < cps.length; i++) sb.appendCodePoint(cps[i]);
        return sb.toString();
    }

    // -------------------------------------------------------------- compose

    @Override
    public Object compose(Object op1, Object op2) {
        if (op1 == null || op2 == null) return null;
        if (op1 instanceof String) return apply(op1, op2);
        if (op2 instanceof String) return op2; // a bare string replaces the document outright
        List<Object> c1 = new ArrayList<>(components(op1));
        List<Object> c2 = new ArrayList<>(components(op2));
        if (c1.isEmpty()) return clean(c2);
        if (c2.isEmpty()) return clean(c1);

        // Walk both operations together, translating everything into the coordinate system of
        // the original document. Everything op2 skips or deletes which op1 inserted cancels out.
        Out out = new Out();
        int i1 = 0, i2 = 0;

        while (i1 < c1.size() && i2 < c2.size()) {
            Object a = c1.get(i1);
            Object b = c2.get(i2);

            if (a instanceof Number) {
                int na = ((Number) a).intValue();
                if (b instanceof Number) {
                    int nb = ((Number) b).intValue();
                    int take = Math.min(na, nb);
                    out.skip(take);
                    na -= take;
                    nb -= take;
                    if (na == 0) i1++; else c1.set(i1, na);
                    if (nb == 0) i2++; else c2.set(i2, nb);
                } else if (b instanceof String) {
                    // op2's insert lands here, ahead of anything op1 does.
                    out.insert((String) b);
                    i2++;
                } else {
                    int take = Math.min(na, deleteLen(b));
                    out.del(take);
                    na -= take;
                    if (na == 0) i1++; else c1.set(i1, na);
                    Object rest = restDel(b, take);
                    if (rest == null) i2++; else c2.set(i2, rest);
                }
            } else if (a instanceof String) {
                String ins = (String) a;
                if (b instanceof String) {
                    out.insert((String) b);
                    i2++;
                } else {
                    // op2 either skips over or deletes the text op1 inserted. Either way those
                    // characters never touch the original document.
                    int n = b instanceof Number ? ((Number) b).intValue() : deleteLen(b);
                    int take = Math.min(cpLen(ins), n);
                    if (!(b instanceof Number)) out.del(0); // deleted characters simply vanish
                    else out.insert(cpSubstring(ins, 0, take));
                    ins = cpSubstring(ins, take, cpLen(ins));
                    if (ins.isEmpty()) i1++; else c1.set(i1, ins);
                    n -= take;
                    if (n == 0) i2++;
                    else c2.set(i2, b instanceof Number ? (Object) n : restDel(b, take));
                }
            } else {
                if (b instanceof String) {
                    out.insert((String) b);
                    i2++;
                } else {
                    out.del(deleteLen(a));
                    i1++;
                }
            }
        }

        for (; i1 < c1.size(); i1++) {
            Object a = c1.get(i1);
            if (a instanceof Number) out.skip(((Number) a).intValue());
            else if (a instanceof String) out.insert((String) a);
            else out.del(deleteLen(a));
        }
        for (; i2 < c2.size(); i2++) {
            Object b = c2.get(i2);
            if (b instanceof Number) out.skip(((Number) b).intValue());
            else if (b instanceof String) out.insert((String) b);
            else out.del(deleteLen(b));
        }

        out.flushIns();
        return clean(out.result);
    }

    /** The unconsumed tail of a delete component, or null if it's been used up. */
    private static Object restDel(Object c, int taken) {
        int n = deleteLen(c);
        if (taken >= n) return null;
        Object d = c instanceof Map ? Json.asMap(c).get("d") : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("d", d instanceof String ? ((String) d).substring(taken) : (Object) (n - taken));
        return m;
    }

    // ------------------------------------------------------------ transform

    @Override
    public Object transform(Object op1, Object op2, Side side) {
        if (op2 == null || (op2 instanceof List && ((List<?>) op2).isEmpty())) return normalize(op1);
        if (op1 == null) return null;

        Other other = new Other(components(op2));
        Out out = new Out();

        for (Object c : components(op1)) {
            if (c instanceof Number) {
                long rem = ((Number) c).longValue();
                while (rem > 0) {
                    if (!other.hasNext()) {
                        out.skip((int) rem);
                        rem = 0;
                        break;
                    }
                    switch (other.kind()) {
                        case NUM: {
                            int take = (int) Math.min(rem, other.avail());
                            other.take(take);
                            out.skip(take);
                            rem -= take;
                            break;
                        }
                        case STR: {
                            // The other operation inserted here. Either we skip past their text (which
                            // counts towards how far we travel) or - if we're done travelling - we leave
                            // their insertion alone. Leaving it alone is what makes our own insertions
                            // end up in front of theirs when we're the left op.
                            out.skip(cpLen(other.str()));
                            other.take(other.avail());
                            break;
                        }
                        case DEL: {
                            int take = (int) Math.min(rem, other.avail());
                            other.take(take);
                            rem -= take; // deleted characters occupy no room in the new document
                            break;
                        }
                    }
                }
            } else if (c instanceof String) {
                if (side == Side.RIGHT) {
                    // We come second: anything they inserted at this spot goes ahead of us.
                    while (other.hasNext() && other.kind() == Kind.STR) {
                        out.skip(cpLen(other.str()));
                        other.take(other.avail());
                    }
                }
                out.insert((String) c);
            } else {
                long rem = deleteLen(c);
                while (rem > 0) {
                    if (!other.hasNext()) {
                        out.del((int) rem);
                        rem = 0;
                        break;
                    }
                    switch (other.kind()) {
                        case NUM: {
                            int take = (int) Math.min(rem, other.avail());
                            other.take(take);
                            out.del(take);
                            rem -= take;
                            break;
                        }
                        case STR: {
                            // Their insertion lives inside what we delete. Their text survives, so we
                            // have to step over it rather than removing it.
                            out.skip(cpLen(other.str()));
                            other.take(other.avail());
                            break;
                        }
                        case DEL: {
                            int take = (int) Math.min(rem, other.avail());
                            other.take(take);
                            rem -= take; // already gone - nothing to do
                            break;
                        }
                    }
                }
            }
        }
        out.flushIns();
        return clean(out.result);
    }

    // ---------------------------------------------------------- misc game

    @Override
    public Object normalize(Object op) {
        if (op instanceof String) return op;
        if (op == null) return null;
        List<Object> c = clean(components(op));
        if (!c.isEmpty() && c.get(c.size() - 1) instanceof Number) c.remove(c.size() - 1);
        return c;
    }

    @Override
    public Boolean isNoop(Object op) {
        if (op == null) return true;
        if (op instanceof List) return ((List<?>) op).isEmpty();
        if (op instanceof String) return Boolean.FALSE;
        return null;
    }

    @Override
    public Object invert(Object op) {
        if (op == null) return null;
        List<Object> out = new ArrayList<>();
        for (Object c : components(op)) {
            if (c instanceof Number) out.add(c);
            else if (c instanceof String) out.add(del((String) c));
            else {
                Map<String, Object> m = Json.asMap(c);
                Object d = m.get("d");
                if (!(d instanceof String)) {
                    throw new IllegalArgumentException("Cannot invert a text delete without knowing its content");
                }
                // The deleted content becomes an insert. Merge with a neighbouring insert.
                if (!out.isEmpty() && out.get(out.size() - 1) instanceof String) {
                    out.set(out.size() - 1, (String) out.get(out.size() - 1) + d);
                } else {
                    out.add(d);
                }
            }
        }
        return clean(out);
    }

    @Override
    public boolean supportsTransformPosition() {
        return true;
    }

    @Override
    public boolean supportsMakeInvertible() {
        return true;
    }

    /**
     * Replace delete shorthands ({@code {d: 5}}) with the text they delete, taken from the
     * document the operation applies to. That's what makes an operation invertible.
     */
    @Override
    public Object makeInvertible(Object op, Object doc) {
        if (!(op instanceof List) || !(doc instanceof String)) return null;
        String snapshot = (String) doc;
        List<Object> result = new ArrayList<>();
        int pos = 0;

        for (Object c : Json.asList(op)) {
            if (c instanceof Number) {
                int n = ((Number) c).intValue();
                int last = result.size() - 1;
                if (last >= 0 && result.get(last) instanceof Number) {
                    result.set(last, ((Number) result.get(last)).intValue() + n);
                } else {
                    result.add(n);
                }
                pos += n;
            } else if (c instanceof String) {
                result.add(c);
            } else {
                int n = deleteLen(c);
                Map<String, Object> del = new LinkedHashMap<>();
                del.put("d", cpSubstring(snapshot, pos, pos + n));
                result.add(del);
                pos += n;
            }
        }
        return result;
    }

    /**
     * Transform a cursor position inside a string through an operation. Null if it was deleted.
     */
    @Override
    public Object transformPosition(Object position, Object op) {
        int pos = ((Number) position).intValue();
        if (op == null) return pos;
        List<Object> comps = components(op);
        int newPos = pos;
        int origPos = 0; // how far into the original document we've walked
        for (Object c : comps) {
            if (c instanceof Number) {
                int n = ((Number) c).intValue();
                origPos += n;
            } else if (c instanceof String) {
                String s = (String) c;
                if (origPos < pos) newPos += cpLen(s);
            } else {
                int n = deleteLen(c);
                if (origPos < pos) {
                    int overlap = Math.min(n, pos - origPos);
                    newPos -= overlap;
                    if (pos < origPos + n) return null; // the character we point at was deleted
                }
                origPos += n;
            }
            if (origPos >= pos && !(c instanceof String)) break;
        }
        return newPos;
    }

    @Override
    public void checkValidOp(Object op) {
        if (op == null) return;
        if (op instanceof String) return;
        if (!(op instanceof List)) throw new IllegalArgumentException("Text op must be a list or string");
        for (Object c : asList(op)) {
            if (c instanceof Number) {
                if (((Number) c).intValue() < 0) throw new IllegalArgumentException("Cannot skip negative chars");
            } else if (c instanceof String) {
                // fine
            } else if (c instanceof Map && Json.asMap(c).containsKey("d")) {
                Object d = Json.asMap(c).get("d");
                if (!(d instanceof Number) && !(d instanceof String)) {
                    throw new IllegalArgumentException("Invalid delete component");
                }
            } else {
                throw new IllegalArgumentException("Invalid component in text op: " + c);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    enum Kind {NUM, STR, DEL}

    /**
     * Number of characters a delete component removes.
     */
    static int deleteLen(Object c) {
        Object d = Json.asMap(c).get("d");
        if (d instanceof String) return cpLen((String) d);
        return ((Number) d).intValue();
    }

    /** Number of unicode code points in the string (not UTF-16 code units). */
    static int cpLen(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Substring between two code point offsets. */
    static String cpSubstring(String s, int fromCp, int toCp) {
        int from = s.offsetByCodePoints(0, Math.min(fromCp, cpLen(s)));
        int to = s.offsetByCodePoints(0, Math.min(toCp, cpLen(s)));
        return s.substring(from, to);
    }

    static Map<String, Object> del(Object content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("d", content);
        return m;
    }

    /**
     * Total number of original characters the operation walks over.
     */
    static int traversalLength(List<Object> comps) {
        int n = 0;
        for (Object c : comps) {
            if (c instanceof Number) n += ((Number) c).intValue();
            else if (!(c instanceof String)) n += deleteLen(c);
        }
        return n;
    }

    /**
     * Clean up an operation: drop zero length components, merge neighbours of the same type and
     * turn a bare string into a whole-document replacement.
     */
    static List<Object> clean(List<Object> comps) {
        List<Object> out = new ArrayList<>();
        for (Object c : comps) {
            if (c instanceof Number) {
                if (((Number) c).intValue() == 0) continue;
                if (!out.isEmpty() && out.get(out.size() - 1) instanceof Number) {
                    out.set(out.size() - 1, ((Number) out.get(out.size() - 1)).intValue() + ((Number) c).intValue());
                    continue;
                }
            } else if (c instanceof String) {
                if (((String) c).isEmpty()) continue;
                if (!out.isEmpty() && out.get(out.size() - 1) instanceof String) {
                    out.set(out.size() - 1, (String) out.get(out.size() - 1) + c);
                    continue;
                }
            } else {
                if (deleteLen(c) == 0) continue;
                if (!out.isEmpty() && !(out.get(out.size() - 1) instanceof Number)
                        && !(out.get(out.size() - 1) instanceof String)) {
                    int prev = deleteLen(out.get(out.size() - 1));
                    out.set(out.size() - 1, del(prev + deleteLen(c)));
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Components of an operation, cleaned but with any trailing skip left in place.
     */
    static List<Object> components(Object op) {
        if (op == null) return new ArrayList<>();
        if (op instanceof String) {
            List<Object> l = new ArrayList<>();
            if (!((String) op).isEmpty()) l.add(op);
            return l;
        }
        return clean(asList(op));
    }

    /**
     * Cursor over another operation, tracking partially consumed components.
     */
    static final class Other {
        final List<Object> comps;
        int idx;
        int consumed;

        Other(List<Object> comps) {
            this.comps = comps;
        }

        boolean hasNext() {
            return idx < comps.size();
        }

        Kind kind() {
            Object c = comps.get(idx);
            if (c instanceof Number) return Kind.NUM;
            if (c instanceof String) return Kind.STR;
            return Kind.DEL;
        }

        String str() {
            return (String) comps.get(idx);
        }

        int len() {
            Object c = comps.get(idx);
            if (c instanceof Number) return ((Number) c).intValue();
            if (c instanceof String) return ((String) c).length();
            return deleteLen(c);
        }

        int avail() {
            return len() - consumed;
        }

        void take(int k) {
            consumed += k;
            if (consumed >= len()) {
                idx++;
                consumed = 0;
            }
        }
    }

    /**
     * Builder which keeps pending skips / deletes / inserts in the right order.
     */
    static final class Out {
        final List<Object> result = new ArrayList<>();
        int pendingSkip;
        int pendingDel;
        StringBuilder pendingIns = new StringBuilder();

        void flushIns() {
            if (pendingIns.length() > 0) {
                result.add(pendingIns.toString());
                pendingIns.setLength(0);
            }
        }

        void flushPending() {
            flushIns();
            if (pendingSkip > 0) {
                addNum(pendingSkip);
                pendingSkip = 0;
            }
            if (pendingDel > 0) {
                addDel(pendingDel);
                pendingDel = 0;
            }
        }

        void skip(int n) {
            if (n <= 0) return;
            if (pendingDel > 0) flushPending();
            pendingSkip += n;
        }

        void skip() {
            skip(1);
        }

        void del(int n) {
            if (n <= 0) return;
            if (pendingSkip > 0) flushPending();
            pendingDel += n;
        }

        void del() {
            del(1);
        }

        void insert(String s) {
            if (s.isEmpty()) return;
            flushPending();
            pendingIns.append(s);
        }

        void addNum(int n) {
            if (!result.isEmpty() && result.get(result.size() - 1) instanceof Number) {
                result.set(result.size() - 1, ((Number) result.get(result.size() - 1)).intValue() + n);
            } else {
                result.add(n);
            }
        }

        void addDel(int n) {
            if (!result.isEmpty() && !(result.get(result.size() - 1) instanceof Number)
                    && !(result.get(result.size() - 1) instanceof String)) {
                result.set(result.size() - 1,
                        TextUnicodeSubtype.del(deleteLen(result.get(result.size() - 1)) + n));
            } else {
                result.add(TextUnicodeSubtype.del(n));
            }
        }
    }
}
