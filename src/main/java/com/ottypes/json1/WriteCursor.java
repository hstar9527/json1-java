package com.ottypes.json1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a JSON1 operation.
 *
 * <p>Operations have a strict canonical form (children sorted, no redundant nesting, no empty
 * components). The write cursor maintains all of those invariants as you append to it, so it's
 * the safe way to build anything but the simplest operations.
 */
public class WriteCursor extends Cursor {

  /** Keys we've been asked to descend into but haven't created yet. */
  private final List<Object> pendingDescent = new ArrayList<>();

  private List<Object> op;

  public WriteCursor() { this(null); }

  public WriteCursor(List<Object> existingOp) {
    super(existingOp);
    this.op = existingOp;
  }

  // ------------------------------------------------------------- movement

  @Override public void ascend() {
    if (!pendingDescent.isEmpty()) pendingDescent.remove(pendingDescent.size() - 1);
    else super.ascend();
  }

  @Override public List<Object> getPath() {
    List<Object> path = super.getPath();
    path.addAll(pendingDescent);
    return path;
  }

  public void reset() { lcIdx = -1; }

  // ------------------------------------------------------------- writing

  /**
   * Create (or return) the operation component at the current location. Components must not be
   * empty operations aren't valid with an empty component, so write something immediately.
   */
  public OpComponent getComponent() {
    flushDescent();
    List<Object> c = Json.asList(container);
    int i = idx + 1;
    if (i < c.size() && Json.isObj(c.get(i))) {
      Object existing = c.get(i);
      if (existing instanceof OpComponent) return (OpComponent) existing;
      OpComponent replacement = new OpComponent();
      replacement.putAll(Json.asMap(existing));
      c.set(i, replacement);
      return replacement;
    }
    OpComponent component = new OpComponent();
    c.add(i, component);
    return component;
  }

  public void write(String key, Object value) {
    OpComponent component = getComponent();
    if (component.containsKey(key) && !Objects.equals(component.get(key), value)) {
      throw Cursor.bug("Overwritten component");
    }
    component.put(key, value);
  }

  /** The operation built so far. */
  public List<Object> get() { return op; }

  // ------------------------------------------------------------ descending

  public void descend(Object key) {
    if (!isValidPathItem(key)) throw new IllegalArgumentException("Invalid JSON key");
    pendingDescent.add(key);
  }

  public WriteCursor descendPath(List<Object> path) {
    pendingDescent.addAll(path);
    return this;
  }

  public WriteCursor at(List<Object> path, Body fn) {
    descendPath(path);
    fn.run(this);
    for (int i = 0; i < path.size(); i++) ascend();
    return this;
  }

  public interface Body {
    void run(WriteCursor w);
  }

  /**
   * Write a single field at the given path. Ordering guarantees are relaxed afterwards so that
   * several of these can be chained together.
   */
  public WriteCursor writeAtPath(List<Object> path, String key, Object value) {
    at(path, w -> w.write(key, value));
    reset();
    return this;
  }

  public WriteCursor writeMove(List<Object> from, List<Object> to, int slot) {
    return writeAtPath(from, "p", slot).writeAtPath(to, "d", slot);
  }

  public WriteCursor writeMove(List<Object> from, List<Object> to) {
    return writeMove(from, to, 0);
  }

  // ------------------------------------------------------------ merging

  public interface MergeFn {
    void merge(Map<String, Object> c, WriteCursor w);
  }

  public static final MergeFn COPY_ALL = (c, w) -> {
    for (Map.Entry<String, Object> e : c.entrySet()) w.write(e.getKey(), e.getValue());
  };

  /** Copy another operation into this one, from the current position downwards. */
  public void mergeTree(List<Object> data) { mergeTree(data, COPY_ALL); }

  public void mergeTree(List<Object> data, MergeFn mergeFn) {
    if (data == null) return;
    if (data == op) throw new IllegalArgumentException("Cannot merge into my own tree");

    int _lcIdx = lcIdx;
    int oldDepth = parents.size();
    int depth = 0;

    for (Object c : data) {
      if (c instanceof String || c instanceof Number) {
        depth++;
        descend(c);
      } else if (c instanceof List) {
        mergeTree(Json.asList(c), mergeFn);
      } else if (c instanceof Map) {
        mergeFn.merge(Json.asMap(c), this);
      }
    }

    while (depth-- > 0) ascend();

    // We might have flushed out pending descents - if so restart the descent bookkeeping.
    lcIdx = (parents.size() == oldDepth) ? _lcIdx : -1;
  }

  // ------------------------------------------------------------ internals

  /** It'd be dangerous to allow __proto__ as a key, given javascript's prototype semantics. */
  public static boolean isValidPathItem(Object k) {
    return k instanceof Integer || (k instanceof String && !"__proto__".equals(k));
  }

  private void flushDescent() {
    if (parents.size() != indexes.size() / 2) {
      throw new AssertionError("internal consistency error in write cursor");
    }
    if (container == null) {
      op = new ArrayList<>();
      container = op;
    }

    for (Object key : pendingDescent) {
      List<Object> c = Json.asList(container);
      int i = idx + 1;

      // Step past our own component, if we have one.
      if (i < c.size() && Json.isObj(c.get(i))) i++;
      assertTrue(i == c.size() || !Json.isObj(c.get(i)), "invalid op structure");

      if (i == c.size()) {
        // Nothing here yet - just append the child key inline.
        c.add(key);
        idx = i;
        continue;
      }
      if (Objects.equals(c.get(i), key)) {
        // We're already here.
        idx = i;
        continue;
      }

      if (!(c.get(i) instanceof List)) {
        // There's already an inline child here, so everything from here on has to become a
        // nested list of children.
        List<Object> oldChild = new ArrayList<>(c.subList(i, c.size()));
        c.subList(i, c.size()).clear();
        c.add(oldChild);
        if (lcIdx > -1) lcIdx = i;
      }

      indexes.add(idx);
      parents.add(container);

      if (lcIdx != -1) {
        assertTrue(Json.isGreaterKey(key, firstKey(c.get(lcIdx))), "out of order descent");
        i = lcIdx + 1;
        lcIdx = -1;
      }

      // Skip forward until we're at the right place in the (sorted) children.
      while (i < c.size() && Json.isGreaterKey(key, firstKey(c.get(i)))) i++;

      indexes.add(i);
      idx = 0;
      if (i < c.size() && Objects.equals(firstKey(c.get(i)), key)) {
        container = c.get(i);
      } else {
        List<Object> child = new ArrayList<>();
        child.add(key);
        c.add(i, child);
        container = child;
      }
    }
    pendingDescent.clear();
  }

  private static Object firstKey(Object child) { return Json.asList(child).get(0); }
}
