package com.ottypes.json1;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads over a JSON1 operation.
 *
 * <p>Usage is to iterate the children at the current position, moving the cursor as you go:
 *
 * <pre>{@code
 *   ReadCursor r = new ReadCursor(op);
 *   for (Object key : r) {
 *     r.descendFirst();  // not needed - the cursor is already positioned at key
 *     ...
 *     r.ascend();
 *   }
 * }</pre>
 *
 * <p>Beware that iterating is destructive: the cursor moves as you iterate, and anything the
 * loop body does to this cursor must be undone before the loop continues. The reference
 * implementation relies on this, and so do we.
 */
public class ReadCursor extends Cursor implements Iterable<Object> {

  public ReadCursor(Object op) { super(op); }

  /** The subtree of the operation from the current position downwards. */
  public List<Object> get() {
    if (container == null) return null;
    List<Object> src = Json.asList(container);
    return new ArrayList<>(src.subList(idx + 1, src.size()));
  }

  /** The key we're standing on. Only valid right after descending into a child. */
  public Object getKey() {
    assertTrue(container != null, "Invalid call to getKey before cursor descended");
    return Json.asList(container).get(idx);
  }

  /** The operation component living at the current position, or null. */
  public Map<String, Object> getComponent() {
    List<Object> l = container instanceof List ? Json.asList(container) : null;
    if (l == null || l.size() <= idx + 1) return null;
    Object c = l.get(idx + 1);
    return Json.isObj(c) ? Json.asMap(c) : null;
  }

  /** Descend into the first child. Returns false if there are no children. */
  public boolean descendFirst() {
    int i = idx + 1;
    List<Object> l = container instanceof List ? Json.asList(container) : null;
    if (l == null || i >= l.size()) return false;
    if (Json.isObj(l.get(i))) {
      if (i + 1 >= l.size()) return false;
      i++;
    }
    Object firstChild = l.get(i);
    if (firstChild instanceof List) {
      indexes.add(idx);
      parents.add(container);
      indexes.add(i);
      idx = 0;
      container = firstChild;
    } else {
      idx = i;
    }
    return true;
  }

  /** Move to the next child at this level. Leaves the cursor unchanged if there isn't one. */
  public boolean nextSibling() {
    if (parents.size() != indexes.size() / 2) {
      throw new AssertionError("internal consistency error in cursor");
    }
    // Inline children (or the root) only ever have one sibling to walk to.
    if (idx > 0 || parents.isEmpty()) return false;

    int i = indexes.get(indexes.size() - 1) + 1;
    Object parent = parents.get(parents.size() - 1);
    List<Object> pc = Json.asList(parent);
    if (i >= pc.size()) return false; // past the end of the nested children

    indexes.set(indexes.size() - 1, i);
    container = pc.get(i);
    return true;
  }

  public ReadCursor clone() {
    ReadCursor c = new ReadCursor(null);
    c.container = this.container;
    c.idx = this.idx;
    c.parents.addAll(this.parents);
    c.indexes.addAll(this.indexes);
    return c;
  }

  /** Iterate over the child keys at this position, leaving the cursor on each child in turn. */
  @Override public Iterator<Object> iterator() {
    return new Iterator<Object>() {
      boolean descended;
      boolean done;

      @Override public boolean hasNext() {
        if (!descended) {
          descended = true;
          if (!descendFirst()) { done = true; return false; }
          return true;
        }
        if (done) return false;
        if (!nextSibling()) { ascend(); done = true; return false; }
        return true;
      }

      @Override public Object next() { return getKey(); }

      @Override public void remove() { throw new UnsupportedOperationException(); }
    };
  }

  /** Visit every component at or below this position, copying the traversal into w if given. */
  public interface Visitor {
    void visit(Map<String, Object> c, WriteCursor w);
  }

  public void traverse(WriteCursor w, Visitor fn) {
    Map<String, Object> c = getComponent();
    if (c != null) fn.visit(c, w);

    for (Object key : this) {
      if (w != null) w.descend(key);
      traverse(w, fn);
      if (w != null) w.ascend();
    }
  }

  public interface SlotVisitor {
    void visit(int slot, WriteCursor w);
  }

  public void eachPick(WriteCursor w, SlotVisitor fn) {
    traverse(w, (c, w2) -> { Integer p = OpComponent.pk(c); if (p != null) fn.visit(p, w2); });
  }

  public void eachDrop(WriteCursor w, SlotVisitor fn) {
    traverse(w, (c, w2) -> { Integer d = OpComponent.dk(c); if (d != null) fn.visit(d, w2); });
  }
}
