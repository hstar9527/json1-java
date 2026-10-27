package com.ottypes.json1;

import java.util.Map;

/**
 * Walks one read cursor along in lockstep with another (typically hidden) traversal.
 *
 * <p>Operations rarely line up key-for-key, because one operation may insert or remove list
 * items which the other operation doesn't know about. {@code advancer} maps keys from the
 * context we're reading in to the context we're writing out, and reports every child we skip
 * over so the caller can keep its own index bookkeeping up to date.
 */
public final class Advancer {

  /** Maps a list index from one context to another. Negative results use ~x to mean "skip". */
  public interface ListMap {
    int map(int k, Map<String, Object> c);
  }

  /** Called for every list child we walk past. */
  public interface ListAdv {
    void advance(int k, Map<String, Object> c);
  }

  private final ReadCursor r;
  private final ListMap listMap;
  private final ListAdv listAdv;
  private boolean valid;
  private final boolean didDescend;

  public Advancer(ReadCursor r) { this(r, null, null); }

  public Advancer(ReadCursor r, ListMap listMap, ListAdv listAdv) {
    this.r = r;
    this.listMap = listMap;
    this.listAdv = listAdv;
    if (r != null) {
      boolean d = r.descendFirst();
      this.valid = d;
      this.didDescend = d;
    } else {
      this.valid = false;
      this.didDescend = false;
    }
  }

  /** Advance to key {@code ktarget}, returning the cursor there (or null if we walked past it). */
  public ReadCursor adv(Object ktarget) {
    while (valid) {
      Object raw = r.getKey();
      Object k2 = raw;
      boolean skip = false;

      if (ktarget != null) {
        if (listMap != null && raw instanceof Integer) {
          int mapped = listMap.map((Integer) raw, r.getComponent());
          if (mapped < 0) {
            mapped = ~mapped;
            skip = true;
          }
          k2 = mapped;
        }

        if (Json.isGreaterKey(k2, ktarget)) return null;
        else if (k2.equals(ktarget) && !skip) return r;
      }

      if (listAdv != null && k2 instanceof Integer) {
        listAdv.advance((Integer) k2, r.getComponent());
      }
      valid = r.nextSibling();
    }
    return null;
  }

  /** Finish the walk, returning the underlying cursor to where we started. */
  public void end() {
    if (didDescend) r.ascend();
  }

  /** Visit the children of both cursors in key order, passing null where one cursor has no child. */
  public interface ChildrenFn {
    void visit(Object key, ReadCursor r1, ReadCursor r2);
  }

  public static void eachChildOf(ReadCursor r1, ReadCursor r2, ChildrenFn fn) {
    boolean hasChild1 = r1 != null && r1.descendFirst();
    boolean descended1 = hasChild1;
    boolean hasChild2 = r2 != null && r2.descendFirst();
    boolean descended2 = hasChild2;

    while (hasChild1 || hasChild2) {
      Object k1 = hasChild1 ? r1.getKey() : null;
      Object k2 = hasChild2 ? r2.getKey() : null;

      if (k1 != null && k2 != null) {
        if (Json.isGreaterKey(k2, k1)) k2 = null;
        else if (!k1.equals(k2)) k1 = null;
      }

      fn.visit(k1 == null ? k2 : k1, k1 != null ? r1 : null, k2 != null ? r2 : null);

      if (k1 != null && hasChild1) hasChild1 = r1.nextSibling();
      if (k2 != null && hasChild2) hasChild2 = r2.nextSibling();
    }

    if (descended1) r1.ascend();
    if (descended2) r2.ascend();
  }
}
