package com.ottypes.json1;

import java.util.List;

/**
 * An OT type which can be embedded inside a JSON1 document (rich text documents, counters,
 * CRDTs, ...). This mirrors the (informal) interface json1 expects from subtypes.
 *
 * <p>Optional operations return {@code null} (or {@link Boolean}) from the base class to say
 * "this subtype doesn't support that". Callers must check before relying on the result.
 */
public abstract class Subtype {

  /** Which side an operation sits on when two operations are transformed against each other. */
  public enum Side { LEFT, RIGHT }

  public String name;
  public String uri;

  public abstract Object apply(Object doc, Object op);

  public abstract Object compose(Object op1, Object op2);

  public abstract Object transform(Object op1, Object op2, Side side);

  /** Optional. Return true if the passed operation does nothing. */
  public Boolean isNoop(Object op) { return null; }

  /** Optional. Returns the inverse operation, or null if unsupported. */
  public Object invert(Object op) { return null; }

  /** Optional. Fills in data in the operation needed to invert it. Null if unsupported. */
  public Object makeInvertible(Object op, Object doc) { return null; }

  /** Optional. Returns the canonical form of the operation, or null if unsupported. */
  public Object normalize(Object op) { return null; }

  /** Optional. Validate the operation. Does nothing by default. */
  public void checkValidOp(Object op) { }

  /** Optional. Transform a position inside the subdocument. Null if unsupported. */
  public Object transformPosition(Object position, Object op) { return null; }

  /** Whether this subtype implements {@link #transformPosition}. */
  public boolean supportsTransformPosition() { return false; }

  /** Whether this subtype implements {@link #makeInvertible}. */
  public boolean supportsMakeInvertible() { return false; }

  @Override public String toString() { return "Subtype(" + name + ")"; }

  /** Convenience: wrap a bare op list. Subtypes take their own native operation format. */
  @SuppressWarnings("unchecked")
  protected static List<Object> asList(Object op) { return (List<Object>) op; }
}
