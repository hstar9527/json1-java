package com.ottypes.json1;

/** The bundled number subtype: operations are simply the amount to add to the value. */
public class NumberSubtype extends Subtype {

  public NumberSubtype() {
    name = "number";
    uri = "http://sharejs.org/types/number";
  }

  @Override public Object apply(Object doc, Object op) { return add(doc, op); }

  @Override public Object compose(Object op1, Object op2) { return add(op1, op2); }

  @Override public Object transform(Object op1, Object op2, Side side) { return op1; }

  @Override public Object normalize(Object op) { return op; }

  @Override public Boolean isNoop(Object op) {
    return op == null || (op instanceof Number && ((Number) op).doubleValue() == 0);
  }

  @Override public Object invert(Object op) {
    return -((Number) op).doubleValue();
  }

  private static Object add(Object a, Object b) {
    double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
    double sum = x + y;
    if (sum == (long) sum && Math.abs(sum) <= Integer.MAX_VALUE) return (int) sum;
    if (sum == (long) sum && Math.abs(sum) < 1e15) return (long) sum;
    return sum;
  }
}
