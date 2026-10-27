package com.ottypes.json1;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single operation component inside a JSON1 operation - the {@code {...}} bit.
 *
 * <p>Components extend a map so that the (mostly generic) code in this library can read
 * and write named slots. Component keys are:
 * <ul>
 *   <li>{@code i} - insert the immediate value here (drop phase)</li>
 *   <li>{@code r} - remove the value which is here (pick phase). The value is optional
 *       bookkeeping used to make operations invertible.</li>
 *   <li>{@code p} - pick the value here up into the given slot (pick phase)</li>
 *   <li>{@code d} - drop the contents of the given slot here (drop phase)</li>
 *   <li>{@code es} / {@code ena} / {@code e}+{@code et} - embedded subtype edit</li>
 * </ul>
 *
 * <p><b>Identity semantics:</b> unlike a plain map, two components are equal only if they
 * are the same object. The reference implementation puts components in {@code Set}s to mean
 * "this particular component in a particular operation", not "a component with these values".
 * Components are also mutated in place in a handful of places, so aliasing matters.
 */
public final class OpComponent extends LinkedHashMap<String, Object> {

  private static final long serialVersionUID = 1L;

  public OpComponent() { super(); }

  @Override public boolean equals(Object o) { return this == o; }

  @Override public int hashCode() { return System.identityHashCode(this); }

  // -- field accessors. `null` means "not present" for the required fields. ---

  /** pick slot, or null. */
  public static Integer pk(Map<String, Object> c) {
    if (c == null) return null;
    Object p = c.get("p");
    return p instanceof Number ? ((Number) p).intValue() : null;
  }

  /** drop slot, or null. */
  public static Integer dk(Map<String, Object> c) {
    if (c == null) return null;
    Object d = c.get("d");
    return d instanceof Number ? ((Number) d).intValue() : null;
  }

  /** the removed content, or null if there is no remove here. */
  public static Object rm(Map<String, Object> c) {
    return c == null ? null : c.get("r");
  }

  /** the inserted literal, or null if there is no insert here. */
  public static Object ins(Map<String, Object> c) {
    return c == null ? null : c.get("i");
  }

  public static boolean hasPick(Map<String, Object> c) {
    return c != null && (pk(c) != null || c.containsKey("r"));
  }

  public static boolean hasDrop(Map<String, Object> c) {
    return c != null && (dk(c) != null || c.containsKey("i"));
  }

  public static String et(Map<String, Object> c) {
    Object v = c == null ? null : c.get("et");
    return v instanceof String ? (String) v : null;
  }
}
