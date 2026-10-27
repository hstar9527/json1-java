package com.ottypes.json1;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared state for walking around inside a JSON1 operation.
 *
 * <p>An operation is a tree mirroring the document. Near the leaves we store children inline
 * (adjacent to their parent's key inside the same list); deeper children are stored as their
 * own nested list. The cursor therefore has two ways to be positioned, and this base class
 * contains all the fiddly bookkeeping for remembering how we got here.
 */
public abstract class Cursor {

  /** Each time we descend we push the container we came from plus two indexes. */
  final List<Object> parents = new ArrayList<>();
  final List<Integer> indexes = new ArrayList<>();

  /** Index of the last nested child we visited. Only meaningful for write cursors. */
  int lcIdx = -1;

  /** The list we're currently inside (null for the root of a noop operation). */
  Object container;

  /**
   * Index inside {@link #container}. {@code idx} points either at the key of the child we're
   * 'standing on' or at the last component we looked at. -1 means "before the first element".
   */
  int idx = -1;

  protected Cursor(Object op) { this.container = op; }

  /** Move back up towards the root, exactly reversing whichever descent we did last. */
  public void ascend() {
    if (parents.size() != indexes.size() / 2) {
      throw new AssertionError("internal consistency error in cursor");
    }

    if (idx == 0) {
      if (!parents.isEmpty()) {
        lcIdx = indexes.remove(indexes.size() - 1);
        container = parents.remove(parents.size() - 1);
        idx = indexes.remove(indexes.size() - 1);
      } else {
        // We were descended into an inline child at the very start of the operation.
        lcIdx = 0;
        idx = -1;
      }
    } else {
      if (idx <= 0) throw new AssertionError("ascending past the root");
      idx--;
      if (Json.isObj(Json.asList(container).get(idx))) idx--;
    }
  }

  /** The path of keys we took to get here. */
  public List<Object> getPath() {
    List<Object> path = new ArrayList<>();
    Object c = container;
    int p = parents.size() - 1;
    int i = idx;
    while (i >= 0) {
      path.add(0, Json.asList(c).get(i));
      if (i == 0) {
        if (p < 0) break;
        i = indexes.get(p * 2);
        c = parents.get(p--);
      } else {
        i -= Json.isObj(Json.asList(c).get(i - 1)) ? 2 : 1;
      }
    }
    return path;
  }

  static void assertTrue(boolean pred, String msg) {
    if (!pred) throw new AssertionError(msg);
  }

  static AssertionError bug(String msg) {
    return new AssertionError("Internal consistency error: " + msg + ". Please file a bug");
  }
}
