package com.ottypes.json1;

/** Thrown by {@link Json1#transform} when it detects a write conflict. */
public class ConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public final Conflict conflict;

  public ConflictException(Conflict conflict) {
    super("Transform detected write conflict");
    this.conflict = conflict;
  }
}
