package com.ottypes.json1;

import java.util.List;

/**
 * Describes a conflict detected while transforming two concurrent operations.
 *
 * <p>{@code op1} and {@code op2} are slices of the two operations which show the user what
 * caused the problem. Conflicts are symmetric: transforming (a, b, left) reports the same
 * conflict as (b, a, right) with op1 / op2 swapped.
 */
public final class Conflict {

  public final ConflictType type;
  public final List<Object> op1;
  public final List<Object> op2;

  public Conflict(ConflictType type, List<Object> op1, List<Object> op2) {
    this.type = type;
    this.op1 = op1;
    this.op2 = op2;
  }

  public Conflict inverted() { return new Conflict(type, op2, op1); }

  @Override public String toString() {
    return "Conflict{" + type + ", op1=" + Json.stringify(op1) + ", op2=" + Json.stringify(op2) + "}";
  }
}
