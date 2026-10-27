package com.ottypes.json1;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ottypes.json1.Subtype.Side;

/**
 * The JSON1 operational transformation type - a java port of
 * <a href="https://github.com/ottypes/json1">ottypes/json1</a>.
 *
 * <p>It is a successor to JSON0. An operation mirrors the shape of the document and contains
 * instructions for three phases, run in this order:
 *
 * <ol>
 *   <li><b>pick up</b> subtrees out of the document into numbered holding slots (or throw them
 *       away with a remove)</li>
 *   <li><b>drop</b> held subtrees back somewhere else in the document, or insert brand new
 *       literal data</li>
 *   <li><b>edit</b> embedded documents (strings, numbers, ...) using their own OT type</li>
 * </ol>
 *
 * <p>Because every phase sees the document as it existed before the operation started, paths in
 * a single operation are exact regardless of the other parts of that same operation.
 *
 * <p>Example:
 * <pre>{@code
 *   Json1 json1 = Json1.INSTANCE;
 *   List<Object> op1 = json1.moveOp(Arrays.asList("a","x"), Arrays.asList("a","y"));
 *   List<Object> op2 = json1.compose(json1.moveOp(Arrays.asList("a"), Arrays.asList("b")),
 *                                    json1.insertOp(Arrays.asList("b","z"), "hi there"));
 *   List<Object> op1_ = json1.transform(op1, op2, Side.LEFT);
 *
 *   Object doc = Json.parse("{\"a\":{\"x\":5}}");
 *   doc = json1.apply(doc, op2);  // {b:{x:5, z:"hi there"}}
 *   doc = json1.apply(doc, op1_); // {b:{y:5, z:"hi there"}}
 * }</pre>
 */
public class Json1 {

  public static final Json1 INSTANCE = new Json1();

  // ---------------------------------------------------------------- subtypes

  /** Registered subtypes, indexed by both name and uri - same table the javascript uses. */
  private static final Map<String, Subtype> SUBTYPES = new LinkedHashMap<>();

  static {
    registerSubtype(new TextUnicodeSubtype());
    registerSubtype(new NumberSubtype());
  }

  public static void registerSubtype(Subtype subtype) {
    if (subtype.name != null) SUBTYPES.put(subtype.name, subtype);
    if (subtype.uri != null) SUBTYPES.put(subtype.uri, subtype);
  }

  static Subtype typeOrThrow(String name) {
    Subtype t = SUBTYPES.get(name);
    if (t == null) throw new IllegalArgumentException("Missing type: " + name);
    return t;
  }

  static Subtype getEditType(Map<String, Object> c) {
    if (c == null) return null;
    String et = OpComponent.et(c);
    if (et != null) return typeOrThrow(et);
    if (c.get("es") != null) return SUBTYPES.get("text-unicode");
    if (c.get("ena") != null) return SUBTYPES.get("number");
    return null;
  }

  /** The embedded operation carried by a component. */
  static Object getEdit(Map<String, Object> c) {
    if (c.get("es") != null) return c.get("es");
    if (c.get("ena") != null) return c.get("ena");
    return c.get("e");
  }

  /** Write an embedded edit into the operation being built. Discards noops unless asked not to. */
  static void writeEdit(WriteCursor w, Subtype type, Object edit) {
    writeEdit(w, type, edit, false);
  }

  static void writeEdit(WriteCursor w, Subtype type, Object edit, boolean preserveNoop) {
    if (!preserveNoop) {
      Boolean noop = type.isNoop(edit);
      if (noop != null && noop) return;
    }
    if ("number".equals(type.name)) w.write("ena", edit);
    else if ("text-unicode".equals(type.name)) w.write("es", edit);
    else {
      w.write("et", type.name);
      w.write("e", edit);
    }
  }

  /** When set, transform() automatically resolves conflicts instead of throwing. */
  private ConflictPred conflictPred;

  public interface ConflictPred {
    boolean allow(Conflict conflict);
  }

  // ------------------------------------------------------- op construction

  /** Remove the value at {@code path}. */
  public List<Object> removeOp(List<Object> path) { return removeOp(path, true); }

  public List<Object> removeOp(List<Object> path, Object value) {
    return new WriteCursor().writeAtPath(path, "r", value).get();
  }

  /** Move the value from one place in the document to another. */
  public List<Object> moveOp(List<Object> from, List<Object> to) {
    return new WriteCursor().writeMove(from, to).get();
  }

  /** Insert brand new data at {@code path}. */
  public List<Object> insertOp(List<Object> path, Object value) {
    return new WriteCursor().writeAtPath(path, "i", value).get();
  }

  /** Replace the value at path. Pass the old value if you care about invertibility. */
  public List<Object> replaceOp(List<Object> path, Object oldVal, Object newVal) {
    return new WriteCursor().at(path, w -> {
      w.write("r", oldVal);
      w.write("i", newVal);
    }).get();
  }

  /** Edit an embedded document at path using another OT type. */
  public List<Object> editOp(List<Object> path, Object typeOrName, Object subOp) {
    return editOp(path, typeOrName, subOp, false);
  }

  public List<Object> editOp(List<Object> path, Object typeOrName, Object subOp, boolean preserveNoop) {
    return new WriteCursor().at(path, w -> writeEdit(w, resolve(typeOrName), subOp, preserveNoop)).get();
  }

  private static Subtype resolve(Object typeOrName) {
    if (typeOrName instanceof Subtype) return (Subtype) typeOrName;
    return typeOrThrow(String.valueOf(typeOrName));
  }

  // ------------------------------------------------------------- validation

  public static boolean debugMode = false;

  public static void setDebug(boolean val) { debugMode = val; }

  /** Throws if the operation isn't valid. */
  public void checkValidOp(Object op) {
    if (op == null) return;
    if (!(op instanceof List)) throw new IllegalArgumentException("Op must be null or a list");
    new Checker().check(op);
  }

  /** True if the operation does nothing. */
  public boolean isNoop(Object op) {
    return op == null || (op instanceof List && Json.asList(op).isEmpty());
  }

  private static final class Checker {
    final Set<Integer> pickedSlots = new HashSet<>();
    final Set<Integer> droppedSlots = new HashSet<>();
    static final int SCALAR = 1, EDIT = 2, DESCENT = 3;

    void check(Object op) {
      checkDescent(Json.asList(op), true);
      if (pickedSlots.size() != droppedSlots.size()) {
        throw new IllegalArgumentException("Mismatched picks and drops in op");
      }
      for (int i = 0; i < pickedSlots.size(); i++) {
        if (!pickedSlots.contains(i) || !droppedSlots.contains(i)) {
          throw new IllegalArgumentException("Pick/drop slots must count from 0");
        }
      }
    }

    void checkComponent(Map<String, Object> e) {
      boolean empty = true;
      boolean hasEdit = false;
      for (Map.Entry<String, Object> entry : e.entrySet()) {
        String k = entry.getKey();
        Object v = entry.getValue();
        empty = false;
        switch (k) {
          case "r":
          case "i":
            // Nothing else to check - any value is fine here.
            break;
          case "p":
            checkNonNegInteger(v);
            if (pickedSlots.contains(intOf(v))) throw new IllegalArgumentException("Duplicate pick slot");
            pickedSlots.add(intOf(v));
            if (e.containsKey("r")) throw new IllegalArgumentException("Cannot pick and remove together");
            break;
          case "d":
            checkNonNegInteger(v);
            if (droppedSlots.contains(intOf(v))) throw new IllegalArgumentException("Duplicate drop slot");
            droppedSlots.add(intOf(v));
            if (e.containsKey("i")) throw new IllegalArgumentException("Cannot drop and insert together");
            break;
          case "et":
            // Just names the subtype used for the edit in 'e'.
            break;
          case "e":
          case "es":
          case "ena": {
            if (hasEdit) throw new IllegalArgumentException("Two edits in the same component");
            hasEdit = true;
            Subtype t = getEditType(e);
            if (t == null) throw new IllegalArgumentException("Missing type in edit");
            t.checkValidOp(getEdit(e));
            break;
          }
          default:
            throw new IllegalArgumentException("Invalid component item '" + k + "'");
        }
      }
      if (empty) throw new IllegalArgumentException("Empty component");
    }

    Object checkDescent(List<Object> descent, boolean isRoot) {
      if (!Json.isList(descent)) throw new IllegalArgumentException("Op must be null or a list");
      if (descent.isEmpty()) throw new IllegalArgumentException("Empty descent");
      if (!isRoot) checkScalar(descent.get(0));

      int last = SCALAR;
      int numDescents = 0;
      Object lastKey = 0;

      for (Object d : descent) {
        if (d == null) throw new IllegalArgumentException("null in operation");
        if (d instanceof List) {
          Object key = checkDescent(Json.asList(d), false);
          if (numDescents > 0) {
            if (lastKey instanceof Integer == key instanceof Integer) {
              if (Json.compareKeys(lastKey, key) >= 0) {
                throw new IllegalArgumentException("descent keys are not in order");
              }
            } else if (!(lastKey instanceof Integer && key instanceof String)) {
              throw new IllegalArgumentException("descent keys are not in order");
            }
          }
          lastKey = key;
          numDescents++;
          last = DESCENT;
        } else if (d instanceof Map) {
          if (last != SCALAR) throw new IllegalArgumentException("Prev not scalar - instead " + last);
          checkComponent(Json.asMap(d));
          last = EDIT;
        } else {
          if (last == DESCENT) throw new IllegalArgumentException("Scalar after descent");
          checkScalar(d);
          if (!WriteCursor.isValidPathItem(d)) {
            throw new IllegalArgumentException("Invalid path key");
          }
          last = SCALAR;
        }
      }

      if (numDescents == 1) {
        throw new IllegalArgumentException("Operation makes multiple descents. Remove some []");
      }
      if (last != EDIT && last != DESCENT) {
        throw new IllegalArgumentException("Operation must end with an edit or descent");
      }
      return descent.get(0);
    }
  }

  private static int intOf(Object v) { return ((Integer) v).intValue(); }

  private static void checkNonNegInteger(Object n) {
    if (!(n instanceof Integer) || (Integer) n < 0) {
      throw new IllegalArgumentException("Expected a non-negative integer");
    }
  }

  private static void checkScalar(Object s) {
    if (s instanceof Integer) checkNonNegInteger(s);
    else if (!(s instanceof String)) throw new IllegalArgumentException("Expected a string key");
  }

  // -------------------------------------------------------------- normalize

  /** Rewrite an operation into its canonical form. */
  public List<Object> normalize(Object op) {
    if (op == null) return null;
    WriteCursor w = new WriteCursor();
    final Map<Integer, Integer> slotMap = new LinkedHashMap<>();
    final int[] nextSlot = {0};

    w.mergeTree(Json.asList(op), (c, writer) -> {
      Subtype t = getEditType(c);
      if (t != null) {
        Object e = getEdit(c);
        Object normalized = t.normalize(e);
        writeEdit(writer, t, normalized == null ? e : normalized);
      }
      for (String k : new String[] {"r", "p", "i", "d"}) {
        if (c.containsKey(k)) {
          Object v = c.get(k);
          if (k.equals("p") || k.equals("d")) {
            Integer src = intOf(v);
            Integer mapped = slotMap.get(src);
            if (mapped == null) slotMap.put(src, mapped = nextSlot[0]++);
            v = mapped;
          }
          writer.write(k, v);
        }
      }
    });
    return w.get();
  }

  // ------------------------------------------------------------------ apply

  /**
   * Apply an operation to a snapshot, returning the new snapshot. The snapshot is copied as it's
   * edited, so the old one stays valid.
   */
  public Object apply(Object snapshot, Object op) {
    checkValidOp(op);
    if (op == null) return snapshot;

    List<Object> held = new ArrayList<>(); // indexed by pick slot
    Object afterPick = pickPhase(snapshot, Json.asList(op), held);
    Object result = dropPhase(afterPick, Json.asList(op), held);
    // A document with everything removed isn't a document any more.
    return result == Json.UNDEFINED ? null : result;
  }

  /**
   * Phase 1: take subtrees out of the document. Subtrees are put in a holding area or discarded.
   * Returns the document with everything removed.
   */
  private static Object pickPhase(Object subDoc, List<Object> descent, List<Object> held) {
    List<Object> stack = new ArrayList<>();

    int i = 0;
    for (; i < descent.size(); i++) {
      Object d = descent.get(i);
      if (d instanceof List) break;
      if (d instanceof Map) continue;
      stack.add(subDoc);
      // It's legal to descend into a null space - you just can't pick from there.
      subDoc = Json.maybeGetChild(subDoc, d);
    }

    // Children are traversed in reverse order here.
    for (int j = descent.size() - 1; j >= i; j--) {
      subDoc = pickPhase(subDoc, Json.asList(descent.get(j)), held);
    }

    // ... and then unwind back to where we started.
    for (--i; i >= 0; i--) {
      Object d = descent.get(i);
      if (!(d instanceof Map)) {
        Object container = stack.remove(stack.size() - 1);
        Object expectedChild = Json.maybeGetChild(container, d);
        if (Json.sameValue(subDoc, expectedChild)) {
          subDoc = container;
        } else if (subDoc == Json.UNDEFINED) {
          subDoc = Json.removeChild(container, d);
        } else {
          subDoc = Json.replaceChild(container, d, subDoc);
        }
      } else if (OpComponent.hasPick(Json.asMap(d))) {
        Map<String, Object> comp = Json.asMap(d);
        Integer slot = OpComponent.pk(comp);
        if (subDoc == Json.UNDEFINED) throw new IllegalArgumentException("Cannot pick up or remove undefined");
        if (slot != null) {
          while (held.size() <= slot) held.add(null);
          held.set(slot, subDoc);
        }
        subDoc = Json.UNDEFINED;
      }
    }
    return subDoc;
  }

  /** Phase 2 &amp; 3: put held subtrees back into the document, insert literals, apply edits. */
  private static Object dropPhase(Object root, List<Object> descent, List<Object> held) {
    return new Dropper(descent, held).run(root);
  }

  private static final class Dropper {
    final List<Object> descent;
    final List<Object> held;

    Object subDoc;
    int i;
    final Map<String, Object> rootContainer = new LinkedHashMap<>();
    int m;
    Object container;
    Object key = "root";

    Dropper(List<Object> descent, List<Object> held) {
      this.descent = descent;
      this.held = held;
      this.container = rootContainer;
    }

    /** Copy-on-write: make sure the parent chain up to the current location is editable. */
    void mut() {
      for (; m < i; m++) {
        Object d = descent.get(m);
        // Skip components and nested descents - neither counts as a path step.
        if (d instanceof Map || d instanceof List) continue;
        if (!Json.isValidKey(container, key)) {
          throw new IllegalArgumentException("Invalid descent in op");
        }
        Object clone = Json.shallowClone(Json.getRaw(container, key));
        Json.setRaw(container, key, clone);
        container = clone;
        key = d;
      }
    }

    Object run(Object rootDoc) {
      subDoc = rootDoc;
      i = 0;
      // Anything the pick phase removed (possibly the whole document) shows up as UNDEFINED,
      // which we leave out of the root container entirely. So does a missing document.
      if (rootDoc != Json.UNDEFINED && rootDoc != null) rootContainer.put("root", rootDoc);

      for (; i < descent.size(); i++) {
        Object d = descent.get(i);

        if (d instanceof List) {
          Object child = new Dropper(Json.asList(d), held).run(subDoc);
          if (child != subDoc && child != Json.UNDEFINED) {
            mut();
            subDoc = Json.setRaw(container, key, child);
          }
        } else if (d instanceof Map) {
          Map<String, Object> comp = Json.asMap(d);
          Integer slot = OpComponent.dk(comp);
          if (slot != null) {
            mut();
            subDoc = Json.insertChildMut(container, key, held.get(slot));
          } else if (comp.containsKey("i")) {
            mut();
            subDoc = Json.insertChildMut(container, key, comp.get("i"));
          }

          Subtype t = getEditType(comp);
          if (t != null) {
            mut();
            subDoc = Json.setRaw(container, key, t.apply(subDoc, getEdit(comp)));
          } else if (comp.containsKey("e")) {
            throw new IllegalArgumentException("Subtype " + comp.get("et") + " undefined");
          }
        } else {
          subDoc = Json.maybeGetChild(subDoc, d);
        }
      }
      return rootContainer.get("root");
    }
  }

  // ------------------------------------------------------- transformPosition

  /**
   * Transform a path through an operation, so it keeps pointing at the same item. Returns null
   * if that item no longer exists after the operation.
   */
  public List<Object> transformPosition(List<Object> path, Object op) {
    checkValidOp(op);
    return new PositionXf(new ReadCursor(asOp(op)), path).run();
  }

  private static final class PositionXf {
    final ReadCursor r;
    final List<Object> path = new ArrayList<>();
    final List<Advancer> advStack = new ArrayList<>();

    boolean removed;
    Integer pickedAtSlot;
    int pickIndex;

    PositionXf(ReadCursor r, List<Object> path) {
      this.r = r;
      this.path.addAll(path);
    }

    List<Object> run() {
      for (int i = 0; ; i++) {
        Object k = i < path.size() ? path.get(i) : null;
        Map<String, Object> c = r.getComponent();

        if (c != null) {
          if (c.containsKey("r")) removed = true;
          else if (OpComponent.pk(c) != null) {
            removed = false;
            pickedAtSlot = OpComponent.pk(c);
            pickIndex = i;
          }
        }

        if (i >= path.size()) break;

        final int[] pickOffset = {0};
        Advancer pickAdv = new Advancer(r, null, (kk, cc) -> {
          if (OpComponent.hasPick(cc)) pickOffset[0]++;
        });
        advStack.add(0, pickAdv);

        boolean hasNext = pickAdv.adv(k) != null;
        if (k instanceof Integer) path.set(i, (Integer) k - pickOffset[0]);
        if (!hasNext) break;
      }

      for (Advancer a : advStack) a.end();

      if (removed) return null;

      if (pickedAtSlot != null) {
        final int[] self = {pickedAtSlot};
        r.eachDrop(null, (slot, w) -> {
          if (slot == self[0]) handleDrop();
        });
      } else {
        handleDrop();
      }
      return path;
    }

    void handleDrop() {
      int i = 0;
      if (pickedAtSlot != null) {
        // We were dropped somewhere else in the document. Teleport there and carry on scanning.
        List<Object> rPath = r.getPath();
        i = rPath.size();
        List<Object> newPath = new ArrayList<>(rPath);
        newPath.addAll(path.subList(pickIndex, path.size()));
        path.clear();
        path.addAll(newPath);
      }

      for (; i < path.size(); i++) {
        Object k = path.get(i);

        Map<String, Object> c = r.getComponent();
        Subtype et = getEditType(c);
        if (et != null && et.supportsTransformPosition()) {
          path.set(i, et.transformPosition(path.get(i), getEdit(c)));
          break;
        }

        final int[] dropOffset = {0};
        Advancer dropAdv = new Advancer(r,
            (kk, cc) -> OpComponent.hasDrop(cc) ? ~(kk - dropOffset[0]) : kk - dropOffset[0],
            (kk, cc) -> {
              if (OpComponent.hasDrop(cc)) dropOffset[0]++;
            });

        boolean hasNext = dropAdv.adv(k) != null;
        if (k instanceof Integer) path.set(i, (Integer) k + dropOffset[0]);
        if (!hasNext) break;
      }
    }
  }

  // ----------------------------------------------------------------- compose

  /**
   * Merge two sequential operations into one. {@code compose(a, b)} applied to a document gives
   * the same result as applying a and then b.
   */
  public List<Object> compose(Object op1, Object op2) {
    checkValidOp(op1);
    checkValidOp(op2);
    if (op1 == null) return Json.asList(op2);
    if (op2 == null) return Json.asList(op1);
    return new Composer(asOp(op1), asOp(op2)).run();
  }

  private static final class Composer {
    final ReadCursor r1, r2;
    final WriteCursor w = new WriteCursor();
    int nextSlot = 0;

    final List<WriteCursor> heldPickWrites = new ArrayList<>();
    final List<WriteCursor> heldDropWrites = new ArrayList<>();
    final List<ReadCursor> held1Pick = new ArrayList<>();
    final List<ReadCursor> held2Drop = new ArrayList<>();
    final List<Integer> p1SlotMap = new ArrayList<>();
    final List<Integer> p2SlotMap = new ArrayList<>();
    final Set<Map<String, Object>> visitedOp2EditCs = new HashSet<>();

    Composer(List<Object> op1, List<Object> op2) {
      this.r1 = new ReadCursor(op1);
      this.r2 = new ReadCursor(op2);
    }

    List<Object> run() {
      // Record where op1 picks things up from and where op2 drops them, so we can teleport.
      r1.traverse(null, (c, wl) -> {
        Integer slot = OpComponent.pk(c);
        if (slot != null) put(held1Pick, slot, r1.clone());
      });
      r2.traverse(null, (c, wl) -> {
        Integer slot = OpComponent.dk(c);
        if (slot != null) put(held2Drop, slot, r2.clone());
      });

      WriteCursor w2 = new WriteCursor();
      xfBoundary(r1, r1.clone(), r2, r2.clone(), Json.UNDEFINED, false, w, w2);

      w.reset();
      w.mergeTree(w2.get());
      w.reset();

      // op1's picks (and removes) are copied straight into the output.
      r1.traverse(w, (c, wl) -> {
        Integer slot1 = OpComponent.pk(c);
        if (slot1 != null) {
          Integer slot = at(p1SlotMap, slot1);
          if (slot != null) wl.write("p", slot);
          WriteCursor hw = at(heldPickWrites, slot1);
          if (hw != null) wl.mergeTree(hw.get());
        } else if (c.containsKey("r")) {
          wl.write("r", c.get("r"));
        }
      });
      w.reset();

      // ... and so are op2's drops.
      r2.traverse(w, (c, wl) -> {
        Integer slot2 = OpComponent.dk(c);
        if (slot2 != null) {
          Integer slot = at(p2SlotMap, slot2);
          if (slot != null) wl.write("d", slot);
          WriteCursor hw = at(heldDropWrites, slot2);
          if (hw != null) wl.mergeTree(hw.get());
        } else if (c.containsKey("i")) {
          wl.write("i", c.get("i"));
        }
        Subtype t = getEditType(c);
        if (t != null && !visitedOp2EditCs.contains(c)) writeEdit(wl, t, getEdit(c));
      });

      List<Object> result = w.get();
      Json1.INSTANCE.checkValidOp(result);
      return result;
    }

    /**
     * Walk the boundary between the two operations. op1's drops and op2's picks live in the
     * document in between the two operations; everything has to be transformed out of that
     * context. {@code litIn}/return value track operation literals (inserts) which are being
     * modified.
     */
    Object xfBoundary(ReadCursor r1Pick, ReadCursor r1Drop, ReadCursor r2Pick, ReadCursor r2Drop,
                      Object litIn, boolean rmParent, WriteCursor wd, WriteCursor wp) {
      if (r1Drop == null && r2Pick == null) {
        throw new AssertionError("xfBoundary called with nothing to do");
      }

      Map<String, Object> c1d = compOf(r1Drop);
      Map<String, Object> c2p = compOf(r2Pick);

      boolean rmHere = c2p != null && c2p.containsKey("r");
      boolean insHere = c1d != null && c1d.containsKey("i");
      Integer drop1Slot = OpComponent.dk(c1d);
      Integer pick2Slot = OpComponent.pk(c2p);

      boolean rmChildren = (rmParent || rmHere) && pick2Slot == null;

      if (pick2Slot != null) {
        r2Drop = at(held2Drop, pick2Slot);
        WriteCursor nw = new WriteCursor();
        put(heldDropWrites, pick2Slot, nw);
        wd = nw;
      } else if (c2p != null && c2p.containsKey("r")) {
        r2Drop = null;
      } else {
        Map<String, Object> c2d = compOf(r2Drop);
        if (c2d != null && OpComponent.dk(c2d) != null) r2Drop = null;
      }
      Map<String, Object> c2d = compOf(r2Drop);

      if (drop1Slot != null) {
        r1Pick = at(held1Pick, drop1Slot);
        WriteCursor nw = new WriteCursor();
        put(heldPickWrites, drop1Slot, nw);
        wp = nw;
        if (!rmChildren) {
          int slot = nextSlot++;
          put(p1SlotMap, drop1Slot, slot);
          wd.write("d", slot);
        } else if (rmParent && !rmHere) {
          wp.write("r", true);
        }
      } else if (c1d != null && c1d.containsKey("i")) {
        r1Pick = null;
      } else {
        Map<String, Object> c1p = compOf(r1Pick);
        if (c1p != null && OpComponent.pk(c1p) != null) r1Pick = null;
      }

      final Object[] litOut = {insHere ? c1d.get("i") : litIn};
      if (insHere && litIn != Json.UNDEFINED) {
        throw new AssertionError("insert inside insert without a literal context");
      }

      // Held so we can write the (possibly rewritten) insert at the very end.
      OpComponent insComponent = (pick2Slot == null
          ? (insHere && !rmParent && !rmHere)
          : litOut[0] != Json.UNDEFINED) ? wd.getComponent() : null;

      if (pick2Slot != null) {
        if (litIn != Json.UNDEFINED || insHere) {
          // Just move the literal onward.
        } else {
          // Pick here and drop here: chain the two moves together.
          int slot = drop1Slot != null ? at(p1SlotMap, drop1Slot) : nextSlot++;
          put(p2SlotMap, pick2Slot, slot);
          wp.write("p", slot);
        }
      } else if (rmHere) {
        if (!insHere && litIn == Json.UNDEFINED) wp.write("r", c2p.get("r"));
      }

      // Embedded edits get composed together.
      Subtype type1 = !rmChildren ? getEditType(c1d) : null;
      Subtype type2 = getEditType(c2d);
      if (type1 != null && type2 != null) {
        if (type1 != type2) throw new IllegalArgumentException("Composing incompatible types");
        writeEdit(wd, type1, type1.compose(getEdit(c1d), getEdit(c2d)));
        visitedOp2EditCs.add(c2d);
      } else if (type1 != null) {
        writeEdit(wd, type1, getEdit(c1d));
      } else if (type2 != null) {
        writeEdit(wd, type2, getEdit(c2d));
        visitedOp2EditCs.add(c2d);
      }

      boolean hasContainerLiteral = litOut[0] != Json.UNDEFINED && litOut[0] != null
          && (litOut[0] instanceof Map || litOut[0] instanceof List);
      final boolean[] isCloned = {false};

      final int[] p1PickOff = {0}, p1DropOff = {0};
      final int[] p2PickOff = {0}, p2DropOff = {0};
      final int[] litOff = {0};

      Advancer p2DropAdv = new Advancer(r2Drop,
          (k, c) -> OpComponent.hasDrop(c) ? (p2DropOff[0] - k - 1) : k - p2DropOff[0],
          (k, c) -> {
            if (OpComponent.hasDrop(c)) p2DropOff[0]++;
          });
      Advancer p1PickAdv = new Advancer(r1Pick,
          (k, c) -> OpComponent.hasPick(c) ? (p1PickOff[0] - k - 1) : k - p1PickOff[0],
          (k, c) -> {
            if (OpComponent.hasPick(c)) p1PickOff[0]++;
          });

      final WriteCursor finalWp = wp;
      final WriteCursor finalWd = wd;

      Advancer.eachChildOf(r1Drop, r2Pick, (inKey, p1DropCursor, p2PickCursor) -> {
        Object p1PickKey = inKey, p2DropKey = inKey, litKey = inKey;
        ReadCursor nextP1Pick = null, nextP2Drop = null;

        if (inKey instanceof Integer) {
          int p2Mid = (Integer) inKey + p2PickOff[0];
          nextP2Drop = p2DropAdv.adv(p2Mid);
          p2DropKey = p2Mid + p2DropOff[0];

          int p1Mid = (Integer) inKey + p1DropOff[0];
          nextP1Pick = p1PickAdv.adv(p1Mid);
          if (OpComponent.hasDrop(compOf(nextP2Drop))) nextP1Pick = null; // but we still advanced
          p1PickKey = p1Mid + p1PickOff[0];

          litKey = (Integer) inKey + litOff[0];

          if ((Integer) p1PickKey < 0) throw new AssertionError("p1PickKey is negative");
          if ((Integer) p2DropKey < 0) throw new AssertionError("p2DropKey is negative");

          boolean hd1 = OpComponent.hasDrop(compOf(p1DropCursor));
          boolean hp2 = OpComponent.hasPick(compOf(p2PickCursor));
          if (hd1 || (hp2 && !rmChildren)) litOff[0]--;
          if (hd1) p1DropOff[0]--;
          if (hp2) p2PickOff[0]--;
        } else {
          nextP1Pick = p1PickAdv.adv(inKey);
          nextP2Drop = p2DropAdv.adv(inKey);
        }

        finalWp.descend(p1PickKey);
        finalWd.descend(p2DropKey);

        Object lit = hasContainerLiteral && !OpComponent.hasDrop(compOf(p1DropCursor))
            ? Json.getRaw(litOut[0], litKey)
            : Json.UNDEFINED;
        Object litResult = xfBoundary(nextP1Pick, p1DropCursor, p2PickCursor, nextP2Drop,
            lit, rmChildren, finalWd, finalWp);

        if (hasContainerLiteral && !rmChildren) {
          if (!Json.sameValue(lit, litResult)) {
            if (!isCloned[0]) {
              litOut[0] = Json.shallowClone(litOut[0]);
              isCloned[0] = true;
            }
            setOrRemoveChild(litOut[0], litKey, litResult);
          }
        } else if (litResult != Json.UNDEFINED) {
          throw new AssertionError("Unexpected literal result");
        }

        finalWd.ascend();
        finalWp.ascend();
      });

      p1PickAdv.end();
      p2DropAdv.end();

      if (insComponent != null) {
        insComponent.put("i", litOut[0]);
        return Json.UNDEFINED;
      } else if (!rmParent && !rmHere && pick2Slot == null) {
        return litOut[0];
      }
      return Json.UNDEFINED;
    }
  }

  /** Replace (or remove) a key inside a container, mutating it. Never inserts. */
  private static void setOrRemoveChild(Object container, Object key, Object child) {
    if (key instanceof Integer) {
      if (!(container instanceof List)) throw new AssertionError("Invalid numerical key");
      List<Object> l = Json.asList(container);
      if ((Integer) key >= l.size()) throw new AssertionError("Key out of bounds");
    } else {
      if (!(container instanceof Map)) throw new AssertionError("Invalid object key");
      Map<String, Object> m = Json.asMap(container);
      if (!m.containsKey((String) key)) throw new AssertionError("Key missing from container");
    }

    if (child == Json.UNDEFINED) {
      if (key instanceof Integer) Json.asList(container).remove((int) (Integer) key);
      else Json.asMap(container).remove((String) key);
    } else {
      Json.setRaw(container, key, child);
    }
  }

  // ------------------------------------------------------------------ invert

  /**
   * Invert an operation. Every remove in the operation must contain the removed content, or the
   * operation isn't invertible - see {@link #makeInvertible} for how to arrange that.
   */
  public List<Object> invert(Object op) {
    checkValidOp(op);
    if (op == null) return null;
    return new Inverter(asOp(op)).run();
  }

  private static final class Inverter {
    final ReadCursor r;
    final WriteCursor w;
    Set<Map<String, Object>> editsToTransform;
    final List<ReadCursor> heldPick = new ArrayList<>();
    final List<WriteCursor> heldWrites = new ArrayList<>();

    Inverter(List<Object> op) {
      this.r = new ReadCursor(op);
      this.w = new WriteCursor();
    }

    List<Object> run() {
      invertSimple(r, w, Json.UNDEFINED);

      if (editsToTransform != null) {
        w.reset();
        transformEdits(r.clone(), r, w);

        if (!heldWrites.isEmpty()) {
          w.reset();
          r.traverse(w, (c, wl) -> {
            Integer slot = OpComponent.pk(c);
            if (slot != null) {
              WriteCursor hw = at(heldWrites, slot);
              if (hw != null) wl.mergeTree(hw.get());
            }
          });
        }
      }

      List<Object> result = w.get();
      Json1.INSTANCE.checkValidOp(result);
      return result;
    }

    /**
     * p -&gt; d, r -&gt; i and vice versa. Edits which apply to something this operation created
     * can be baked in directly; the rest need a second pass.
     */
    Object invertSimple(ReadCursor r, WriteCursor w, Object subDoc) {
      Map<String, Object> c = r.getComponent();
      Object insertHere = Json.UNDEFINED;
      boolean subdocModified = false;
      Object current = subDoc;

      if (c != null) {
        Integer pickSlot = OpComponent.pk(c);
        if (pickSlot != null) {
          w.write("d", pickSlot);
          put(heldPick, pickSlot, r.clone());
        }
        if (c.containsKey("r")) w.write("i", c.get("r"));

        Integer dropSlot = OpComponent.dk(c);
        if (dropSlot != null) {
          w.write("p", dropSlot);
          current = Json.UNDEFINED;
        }
        if (c.containsKey("i")) {
          current = insertHere = c.get("i");
        }

        Subtype t = getEditType(c);
        if (t != null) {
          if (current == Json.UNDEFINED) {
            if (editsToTransform == null) editsToTransform = new HashSet<>();
            editsToTransform.add(c);
          } else {
            current = t.apply(current, getEdit(c));
            subdocModified = true;
          }
        }
      }

      // Tracks list items we've inserted into an insert's literal, so we can index correctly.
      final int[] dropOff = {0};

      for (Object key : r) {
        w.descend(key);

        Object raw = key instanceof Integer ? (Integer) key - dropOff[0] : key;
        Object childIn = Json.maybeGetChild(current, raw);
        if (OpComponent.hasDrop(r.getComponent())) dropOff[0]++;

        Object childOut = invertSimple(r, w, childIn);

        if (current != Json.UNDEFINED && childOut != Json.UNDEFINED) {
          if (!subdocModified) {
            subdocModified = true;
            current = Json.shallowClone(current);
          }
          if (!Json.isValidKey(current, raw)) {
            throw new IllegalArgumentException("Cannot modify child - invalid operation");
          }
          Json.setRaw(current, raw, childOut);
        }

        w.ascend();
      }

      if (insertHere != Json.UNDEFINED) {
        w.write("r", current);
        return Json.UNDEFINED;
      }
      return subdocModified ? current : Json.UNDEFINED;
    }

    /** Second pass: move edits which weren't inside an insert back to where the data came from. */
    void transformEdits(ReadCursor rPick, ReadCursor rDrop, WriteCursor w) {
      Map<String, Object> cd = rDrop.getComponent();
      if (cd != null) {
        Integer dropSlot = OpComponent.dk(cd);
        if (dropSlot != null) {
          rPick = at(heldPick, dropSlot);
          WriteCursor nw = new WriteCursor();
          put(heldWrites, dropSlot, nw);
          w = nw;
        }
        if (editsToTransform.contains(cd)) {
          Subtype t = getEditType(cd);
          Object inverted = t.invert(getEdit(cd));
          if (inverted == null) throw new IllegalArgumentException("Cannot invert subtype " + t.name);
          writeEdit(w, t, inverted);
        }
      }

      final int[] pickOff = {0}, dropOff = {0};
      Advancer ap = new Advancer(rPick,
          (k, c) -> OpComponent.hasPick(c) ? (pickOff[0] - k - 1) : k - pickOff[0],
          (k, c) -> {
            if (OpComponent.hasPick(c)) pickOff[0]++;
          });

      for (Object key : rDrop) {
        if (key instanceof Integer) {
          int mid = (Integer) key - dropOff[0];
          ReadCursor nextPick = ap.adv(mid);
          int raw = mid + pickOff[0];
          w.descend(raw);
          transformEdits(nextPick, rDrop, w);
          if (OpComponent.hasDrop(rDrop.getComponent())) dropOff[0]++;
          w.ascend();
        } else {
          w.descend(key);
          transformEdits(ap.adv(key), rDrop, w);
          w.ascend();
        }
      }
      ap.end();
    }
  }

  /** Fill in any data an operation needs to be invertible, using the document it applied to. */
  public List<Object> makeInvertible(Object op, Object doc) {
    if (op == null) return null;
    List<Object> opList = asOp(op);
    // Operations which don't remove anything (and have no embedded edits needing help) are
    // already invertible.
    if (!anyComponent(opList, c -> c.containsKey("r")
        || (getEditType(c) != null && getEditType(c).supportsMakeInvertible()))) {
      return opList;
    }
    return new InvertibleMaker(opList, doc).run();
  }

  /** Invert an operation using the document it applied to (before it was applied). */
  public List<Object> invertWithDoc(Object op, Object doc) {
    return invert(makeInvertible(op, doc));
  }

  private static final class InvertibleMaker {
    final ReadCursor r;
    final WriteCursor w;
    final Object doc;
    boolean hasEdits;
    final List<ReadCursor> heldPick = new ArrayList<>();
    final List<Object> heldDoc = new ArrayList<>();

    InvertibleMaker(List<Object> op, Object doc) {
      this.r = new ReadCursor(op);
      this.w = new WriteCursor();
      this.doc = doc;
    }

    List<Object> run() {
      traversePick(r, w, doc);
      if (hasEdits) {
        w.reset();
        traverseDrop(r.clone(), r, w, doc, false);
      }
      List<Object> result = w.get();
      Json1.INSTANCE.checkValidOp(result);
      return result;
    }

    Object traversePick(ReadCursor r, WriteCursor w, Object subDoc) {
      Map<String, Object> c = r.getComponent();
      boolean modified = false;
      Object current = subDoc;

      if (c != null) {
        if (OpComponent.dk(c) != null) w.write("d", OpComponent.dk(c));
        if (c.containsKey("i")) w.write("i", c.get("i"));

        Integer pickSlot = OpComponent.pk(c);
        if (pickSlot != null) {
          put(heldPick, pickSlot, r.clone());
          if (current == Json.UNDEFINED) {
            throw new IllegalArgumentException("Operation picks up at an invalid key");
          }
          put(heldDoc, pickSlot, current);
          w.write("p", pickSlot);
        }
        if (c.containsKey("r") && current == Json.UNDEFINED) {
          throw new IllegalArgumentException("Invalid doc / op in makeInvertible: removed item missing from doc");
        }
        Subtype t = getEditType(c);
        if (t != null) {
          // Edits on subtypes which can fill in their own missing data get a second pass, once
          // we know which document they actually ended up applying to.
          if (t.supportsMakeInvertible()) hasEdits = true;
          else writeEdit(w, t, getEdit(c), true);
        }
      }

      // Removing items from a list as we traverse it will mess up our own indexes.
      final int[] listOff = {0};

      for (Object key : r) {
        w.descend(key);
        Object rawKey = key instanceof Integer ? (Integer) key - listOff[0] : key;
        Object childIn = Json.maybeGetChild(current, rawKey);
        Object childOut = traversePick(r, w, childIn);

        if (!Json.sameValue(childIn, childOut) || childIn == Json.UNDEFINED && childOut != Json.UNDEFINED) {
          // nothing
        }
        if (childIn != childOut) {
          if (!modified) {
            modified = true;
            current = Json.shallowClone(current);
          }
          if (childOut == Json.UNDEFINED) {
            current = Json.removeChild(current, rawKey);
            if (rawKey instanceof Integer) listOff[0]++;
          } else {
            Json.setRaw(current, rawKey, childOut);
          }
        }
        w.ascend();
      }

      if (c != null) {
        if (c.containsKey("r")) {
          w.write("r", Json.deepClone(current));
          current = Json.UNDEFINED;
        } else if (OpComponent.pk(c) != null) {
          current = Json.UNDEFINED;
        }
      }
      return current;
    }

    void traverseDrop(ReadCursor rPick, ReadCursor rDrop, WriteCursor w, Object subDoc, boolean isLiteral) {
      Map<String, Object> c = rDrop.getComponent();
      Object current = subDoc;
      boolean literal = isLiteral;

      if (c != null) {
        if (c.containsKey("i")) {
          current = c.get("i");
          literal = true;
        } else if (OpComponent.dk(c) != null) {
          int slot = OpComponent.dk(c);
          current = at(heldDoc, slot);
          rPick = at(heldPick, slot);
          literal = false;
        }
        Subtype t = getEditType(c);
        if (t != null) {
          Object made = t.makeInvertible(getEdit(c), current);
          if (made != null) writeEdit(w, t, made, true);
        }
      }

      final int[] pickOff = {0}, dropOff = {0};
      Advancer ap = new Advancer(rPick,
          (k, cc) -> OpComponent.hasPick(cc) ? (pickOff[0] - k - 1) : k - pickOff[0],
          (k, cc) -> {
            if (OpComponent.hasPick(cc)) pickOff[0]++;
          });

      for (Object key : rDrop) {
        if (key instanceof Integer) {
          int mid = (Integer) key - dropOff[0];
          ReadCursor nextPick = ap.adv(mid);
          int raw = mid + pickOff[0];
          Object child = Json.maybeGetChild(current, literal ? mid : raw);
          w.descend(key);
          traverseDrop(nextPick, rDrop, w, child, literal);
          if (OpComponent.hasDrop(rDrop.getComponent())) dropOff[0]++;
          w.ascend();
        } else {
          Object child = Json.maybeGetChild(current, key);
          w.descend(key);
          traverseDrop(ap.adv(key), rDrop, w, child, literal);
          w.ascend();
        }
      }
      ap.end();
    }
  }

  // -------------------------------------------------------------- transform

  /** Result of {@link #tryTransform}: either the transformed op or the conflict which stopped us. */
  public static final class TransformResult {
    public final boolean ok;
    public final List<Object> result;
    public final Conflict conflict;

    TransformResult(List<Object> result) { this.ok = true; this.result = result; this.conflict = null; }

    TransformResult(Conflict conflict) { this.ok = false; this.result = null; this.conflict = conflict; }
  }

  /**
   * Transform two concurrent operations against each other.
   *
   * <p>Note that this bails as soon as it finds a conflict, so a single call doesn't necessarily
   * find <em>all</em> the conflicts between two operations.
   */
  public TransformResult tryTransform(Object op1, Object op2, Side side) {
    checkValidOp(op1);
    checkValidOp(op2);
    return new Transformer(asOp(op1), asOp(op2), side).run();
  }

  public TransformResult tryTransform(Object op1, Object op2, String side) {
    return tryTransform(op1, op2, Side.valueOf(side.toUpperCase()));
  }

  /** Transform, throwing a {@link ConflictException} if the operations conflict. */
  public List<Object> transform(Object op1, Object op2, Side side) {
    if (conflictPred != null) return transformWithConflictsPred(conflictPred, op1, op2, side);
    TransformResult res = tryTransform(op1, op2, side);
    if (res.ok) return res.result;
    throw new ConflictException(res.conflict);
  }

  public List<Object> transform(Object op1, Object op2, String side) {
    return transform(op1, op2, Side.valueOf(side.toUpperCase()));
  }

  /** Transform, silently resolving conflicts by deleting the offending content. */
  public List<Object> transformNoConflict(Object op1, Object op2, Side side) {
    return transformWithConflictsPred(c -> true, op1, op2, side);
  }

  public List<Object> transformNoConflict(Object op1, Object op2, String side) {
    return transformNoConflict(op1, op2, Side.valueOf(side.toUpperCase()));
  }

  /** A view of this type whose transform() resolves conflicts your predicate accepts. */
  public Json1 typeAllowingConflictsPred(ConflictPred pred) {
    Json1 t = new Json1();
    t.conflictPred = pred;
    return t;
  }

  /** Transform, calling {@code allowConflict} for each conflict until none are left. */
  public List<Object> transformWithConflictsPred(ConflictPred allowConflict, Object op1, Object op2, Side side) {
    List<Object> r2Aggregate = null;
    Object _op1 = op1, _op2 = op2;

    while (true) {
      TransformResult res = tryTransform(_op1, _op2, side);
      if (res.ok) return r2Aggregate == null ? res.result : compose(r2Aggregate, res.result);

      Conflict conflict = res.conflict;
      if (!allowConflict.allow(conflict)) throw new ConflictException(conflict);

      Object[] recovery = resolveConflict(conflict, side);
      List<Object> r1 = (List<Object>) recovery[0];
      List<Object> r2 = (List<Object>) recovery[1];

      // Normalizing works around a bug where a non-normalized operation generates a conflict
      // which can never be resolved, looping forever.
      _op1 = compose(normalize(_op1), r1);
      _op2 = compose(normalize(_op2), r2);
      r2Aggregate = r2Aggregate == null ? r2 : compose(r2Aggregate, r2);
    }
  }

  /** An operation which removes whatever the given operation inserts or edits. */
  private static List<Object> opThatRemovesDE(List<Object> op) {
    WriteCursor w = new WriteCursor();
    new ReadCursor(op).traverse(w, (c, wl) -> {
      if (OpComponent.hasDrop(c) || getEditType(c) != null) wl.write("r", true);
    });
    return w.get();
  }

  /** Decide how to recover from a conflict. Returns a pair of operations to append to each side. */
  private static Object[] resolveConflict(Conflict conflict, Side side) {
    switch (conflict.type) {
      case DROP_COLLISION:
        return side == Side.LEFT
            ? new Object[] {null, opThatRemovesDE(conflict.op2)}
            : new Object[] {opThatRemovesDE(conflict.op1), null};

      case RM_UNEXPECTED_CONTENT: {
        final boolean[] op1HasRemove = {false};
        new ReadCursor(conflict.op1).traverse(null, (c, wl) -> {
          if (c.containsKey("r")) op1HasRemove[0] = true;
        });
        return op1HasRemove[0]
            ? new Object[] {null, opThatRemovesDE(conflict.op2)}
            : new Object[] {opThatRemovesDE(conflict.op1), null};
      }

      case BLACKHOLE:
        // Both operations moved their content into each other. There's nowhere sensible to put
        // it, so everything involved gets deleted.
        return new Object[] {opThatRemovesDE(conflict.op1), opThatRemovesDE(conflict.op2)};

      default:
        throw new IllegalArgumentException("Unrecognised conflict: " + conflict.type);
    }
  }

  // ------------------------------------------------------------- utilities

  /** Convert an operation's components into identity-carrying {@link OpComponent}s. */
  static List<Object> asOp(Object op) {
    if (op == null) return null;
    List<Object> src = Json.asList(op);
    List<Object> out = new ArrayList<>(src.size());
    for (Object o : src) {
      if (o instanceof Map && !(o instanceof OpComponent)) {
        OpComponent c = new OpComponent();
        c.putAll(Json.asMap(o));
        out.add(c);
      } else if (o instanceof List) {
        out.add(asOp(o));
      } else {
        out.add(o);
      }
    }
    return out;
  }

  /** The component at a cursor's position, tolerating a null cursor. */
  static Map<String, Object> compOf(ReadCursor r) { return r == null ? null : r.getComponent(); }

  static <T> T at(List<T> l, int i) {
    return i >= 0 && i < l.size() ? l.get(i) : null;
  }

  static <T> void put(List<T> l, int i, T v) {
    while (l.size() <= i) l.add(null);
    l.set(i, v);
  }

  /** Shallow clone of an operation tree (components are shared). */
  static List<Object> shallowCloneOp(List<Object> op) {
    if (op == null) return null;
    List<Object> result = new ArrayList<>(op);
    for (int i = 0; i < result.size(); i++) {
      if (result.get(i) instanceof List) result.set(i, shallowCloneOp(Json.asList(result.get(i))));
    }
    return result;
  }

  static boolean containsValue(List<Integer> list, Integer v) {
    for (Integer i : list) if (i != null && i.equals(v)) return true;
    return false;
  }

  /** True if any component of the operation matches the predicate. */
  private static boolean anyComponent(List<Object> op, ComponentPred fn) {
    for (Object c : op) {
      if (c instanceof List) {
        if (anyComponent(Json.asList(c), fn)) return true;
      } else if (c instanceof Map) {
        if (fn.test(Json.asMap(c))) return true;
      }
    }
    return false;
  }

  private interface ComponentPred {
    boolean test(Map<String, Object> c);
  }
}
