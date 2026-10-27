package com.ottypes.json1;

/** The kinds of conflict {@code tryTransform} can detect. */
public enum ConflictType {
  /** Somebody moved or inserted new content into somewhere the other operation deleted. */
  RM_UNEXPECTED_CONTENT(1),
  /** Both operations tried to drop different content at the same location. */
  DROP_COLLISION(2),
  /** Both operations moved content inside each other. There's nowhere to put the result. */
  BLACKHOLE(3);

  public final int value;

  ConflictType(int value) { this.value = value; }

  public static ConflictType fromValue(int v) {
    for (ConflictType t : values()) if (t.value == v) return t;
    throw new IllegalArgumentException("Unknown conflict type " + v);
  }
}
