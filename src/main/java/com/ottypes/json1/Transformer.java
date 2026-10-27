package com.ottypes.json1;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ottypes.json1.Subtype.Side;

/**
 * The implementation of {@code tryTransform} - the hairiest part of the library.
 *
 * <p>Transforming two concurrent operations means working out what each operation should look
 * like when it's applied <em>after</em> the other one. Each operation is really made of two mini
 * operations (its pick phase and its drop phase), and those live in different document contexts:
 *
 * <pre>
 *     original doc  --(op1 pick)-->  after op1  --(op1 drop)-->  doc after both
 *     original doc  --(op2 pick)-->  after op2  --(op2 drop)-->  doc after both
 * </pre>
 *
 * <p>The plan mirrors the reference implementation:
 * <ol>
 *   <li>scan op2's picks, noting anything op1 removed and any pickup the two ops share</li>
 *   <li>scan op2's drops, noting destinations op1 removed</li>
 *   <li>walk op1's picks through op2, writing them into the output</li>
 *   <li>walk op1's drops back through op1's picks, through op2, then forwards again</li>
 *   <li>copy over anything of op1's which op2 moved around</li>
 *   <li>detect blackholes - the two operations moving content inside each other</li>
 * </ol>
 */
final class Transformer {

  private final Side side;
  private final ReadCursor r1, r2;
  private final WriteCursor w = new WriteCursor();

  private Conflict conflict;
  private int nextSlot = 0;

  // Read cursors into interesting locations in each operation, so we can teleport (and so
  // conflicts can report where things happened).
  private final List<ReadCursor> heldOp1PickByOp1 = new ArrayList<>();
  private final List<ReadCursor> heldOp1DropByOp1 = new ArrayList<>();
  private final List<ReadCursor> heldOp2PickByOp2 = new ArrayList<>();
  private final List<ReadCursor> heldOp2DropByOp2 = new ArrayList<>();
  private final List<ReadCursor> heldOp1PickByOp2 = new ArrayList<>();
  private final List<ReadCursor> heldOp2PickByOp1 = new ArrayList<>();
  private final List<ReadCursor> heldOp2DropByOp1 = new ArrayList<>();
  private final List<ReadCursor> heldOp2RmForOp1 = new ArrayList<>();
  private final List<ReadCursor> heldOp1RmForOp2 = new ArrayList<>();

  /** op2 slots whose pick came from somewhere op1 deletes. */
  private final List<Boolean> cancelledOp2 = new ArrayList<>();
  /** op2 slots whose drop destination was deleted by op1. */
  private final List<Boolean> discardedOp2Drop = new ArrayList<>();

  private final List<WriteCursor> heldPickWrites = new ArrayList<>();
  private final List<WriteCursor> heldDropWrites = new ArrayList<>();

  /** op2 slot -> op1 slot, when both operations picked up the same thing. */
  private final List<Integer> op1PickAtOp2Pick = new ArrayList<>();

  /** op1 slot -> list of op2 slots whose drop landed inside that pick. Used for blackholes. */
  private final List<List<Integer>> op1PicksOp2DropSlots = new ArrayList<>();

  private final List<Map<String, Object>> pickComponents = new ArrayList<>();
  private Set<Map<String, Object>> cancelledRemoves;

  /** output slot -> op1 slot. */
  private final List<Integer> outputSlotMap = new ArrayList<>();

  private final List<ReadCursor> heldOutDropRead = new ArrayList<>();
  private final List<WriteCursor> heldOutDropWrites = new ArrayList<>();

  Transformer(List<Object> op1, List<Object> op2, Side side) {
    this.r1 = new ReadCursor(op1);
    this.r2 = new ReadCursor(op2);
    this.side = side;
  }

  // -------------------------------------------------------------------- run

  Json1.TransformResult run() {
    if (op1Null()) {
      return new Json1.TransformResult((List<Object>) null);
    }

    scanOp2Pick(r2, r1, null);
    if (conflict != null) return failed();

    scanOp2Drop(r1, r2, r2.clone(), null, null);
    if (conflict != null) return failed();

    writeOp1Pick(r1, r2, r2.clone(), w, null);
    w.reset();

    writeOp1Drop(r1, r1.clone(), r2, r2.clone(), w, null);
    if (conflict != null) return failed();
    w.reset();

    // Anything op2 moved out of a location op1 deleted needs removing; and any op1 content
    // which op2 picked up needs writing at op2's drop location.
    if (!cancelledOp2.isEmpty() || !heldPickWrites.isEmpty()) {
      eachDrop(r2, w, (slot2, r, wc) -> {
        if (Json1.at(cancelledOp2, slot2) != null && Json1.at(discardedOp2Drop, slot2) == null) {
          wc.write("r", true);
        }
        WriteCursor hw = Json1.at(heldPickWrites, slot2);
        if (hw != null) wc.mergeTree(hw.get());
      });
      w.reset();
    }

    if ((!heldDropWrites.isEmpty() || !cancelledOp2.isEmpty()) && conflict == null) {
      ReadCursor rOut = new ReadCursor(Json1.shallowCloneOp(w.get()));
      eachDrop(rOut, null, (slotOut, r, wc) -> Json1.put(heldOutDropRead, slotOut, r.clone()));
      for (WriteCursor hdw : heldDropWrites) {
        if (hdw != null) {
          final List<Object> data = hdw.get();
          eachDrop(new ReadCursor(data), null, (slotOut, r, wc) -> Json1.put(heldOutDropRead, slotOut, r.clone()));
        }
      }
      writeHeldOp2Drop(r2, rOut, rOut.clone(), w, null, false);
      w.reset();
      if (conflict != null) return failed();

      if (!heldOutDropWrites.isEmpty()) {
        final List<Object> heldOutDropContent = new ArrayList<>();
        for (WriteCursor h : heldOutDropWrites) heldOutDropContent.add(h != null ? h.get() : null);

        ReadCursor rOut2 = new ReadCursor(Json1.shallowCloneOp(w.get()));
        eachDrop(rOut2, w, (slotOut, r, wc) -> {
          Object data = Json1.at(heldOutDropContent, slotOut);
          if (data != null) {
            wc.mergeTree(Json.asList(data));
            Json1.put(heldOutDropContent, slotOut, null);
          }
        });

        if (anyNonNull(heldOutDropContent)) {
          conflict = buildBlackholeConflict(heldOutDropContent);
        }
      }
    }

    if (conflict != null) return failed();

    List<Object> result = w.get();
    Json1.INSTANCE.checkValidOp(result);
    return new Json1.TransformResult(result);
  }

  private boolean op1Null() { return r1.get() == null; }

  private Json1.TransformResult failed() { return new Json1.TransformResult(conflict); }

  private static boolean anyNonNull(List<Object> l) {
    for (Object o : l) if (o != null) return true;
    return false;
  }

  // ------------------------------------------------------------- scanOp2Pick

  /**
   * Preliminary scan of op2's pick phase, in the context of the original document. Marks op2
   * moves out of things op1 removes, and pickups shared by both operations.
   */
  private void scanOp2Pick(ReadCursor r2Pick, ReadCursor r1Pick, ReadCursor removed1) {
    Map<String, Object> c1 = compOf(r1Pick);
    if (c1 != null) {
      if (c1.containsKey("r")) removed1 = r1Pick.clone();
      else if (OpComponent.pk(c1) != null) {
        removed1 = null;
        Json1.put(heldOp2PickByOp1, OpComponent.pk(c1), r2Pick.clone());
      }
    }

    Map<String, Object> c2 = r2Pick.getComponent();
    Integer slot2;
    if (c2 != null && (slot2 = OpComponent.pk(c2)) != null) {
      Json1.put(heldOp1PickByOp2, slot2, r1Pick != null ? r1Pick.clone() : null);
      Json1.put(heldOp2PickByOp2, slot2, r2Pick.clone());
      if (removed1 != null) {
        Json1.put(cancelledOp2, slot2, Boolean.TRUE);
        Json1.put(heldOp1RmForOp2, slot2, removed1);
      }
      // Both operations picked up the same item. One move gets cancelled, symmetrically.
      if (c1 != null && OpComponent.pk(c1) != null) {
        Json1.put(op1PickAtOp2Pick, slot2, OpComponent.pk(c1));
      }
    }

    Advancer ap1 = new Advancer(r1Pick);
    for (Object key : r2Pick) scanOp2Pick(r2Pick, ap1.adv(key), removed1);
    ap1.end();
  }

  // ------------------------------------------------------------- scanOp2Drop

  /**
   * Scan op2's drop destinations, tracking anything dropped into content op1 removed. Returns
   * true if something was dropped here.
   */
  private boolean scanOp2Drop(ReadCursor r1Pick, ReadCursor r2Pick, ReadCursor r2Drop,
                              Integer pickSlot1, ReadCursor removed1) {
    Map<String, Object> c2d = r2Drop.getComponent();
    boolean droppedHere = false;
    Integer slot2;

    if (c2d != null) {
      if ((slot2 = OpComponent.dk(c2d)) != null) {
        Json1.put(heldOp2DropByOp2, slot2, r2Drop.clone());

        if (pickSlot1 != null) {
          List<Integer> containing = Json1.at(op1PicksOp2DropSlots, pickSlot1);
          if (containing == null) {
            containing = new ArrayList<>();
            Json1.put(op1PicksOp2DropSlots, pickSlot1, containing);
          }
          containing.add(slot2);
        }

        r1Pick = Json1.at(heldOp1PickByOp2, slot2);
        r2Pick = Json1.at(heldOp2PickByOp2, slot2);

        if (Json1.at(cancelledOp2, slot2) != null) {
          if (removed1 != null) Json1.put(discardedOp2Drop, slot2, Boolean.TRUE);
          removed1 = Json1.at(heldOp1RmForOp2, slot2);
        } else if (removed1 != null && (side == Side.RIGHT || Json1.at(op1PickAtOp2Pick, slot2) == null)) {
          if (conflict == null) {
            conflict = new Conflict(ConflictType.RM_UNEXPECTED_CONTENT,
                Json1.INSTANCE.removeOp(removed1.getPath()),
                Json1.INSTANCE.moveOp(r2Pick.getPath(), r2Drop.getPath()));
          }
        }
        droppedHere = true;
      } else if (c2d.containsKey("i")) {
        r1Pick = r2Pick = null;
        droppedHere = true;
        if (removed1 != null) {
          if (conflict == null) {
            conflict = new Conflict(ConflictType.RM_UNEXPECTED_CONTENT,
                Json1.INSTANCE.removeOp(removed1.getPath()),
                Json1.INSTANCE.insertOp(r2Drop.getPath(), c2d.get("i")));
          }
        }
      }
    }

    Map<String, Object> c1p = compOf(r1Pick);
    if (c1p != null) {
      if (c1p.containsKey("r")) removed1 = r1Pick.clone();
      else if (OpComponent.pk(c1p) != null) {
        pickSlot1 = OpComponent.pk(c1p);
        removed1 = null;
      }
    }

    Subtype t2 = getEditType(c2d);
    if (t2 != null && removed1 != null) {
      if (conflict == null) {
        conflict = new Conflict(ConflictType.RM_UNEXPECTED_CONTENT,
            Json1.INSTANCE.removeOp(removed1.getPath()),
            Json1.INSTANCE.editOp(r2Drop.getPath(), t2, Json1.getEdit(c2d), true));
      }
    }

    final int[] p2PickOff = {0}, p2DropOff = {0};
    Advancer ap2 = new Advancer(r2Pick,
        (k, c) -> OpComponent.hasPick(c) ? (p2PickOff[0] - k - 1) : k - p2PickOff[0],
        (k, c) -> {
          if (OpComponent.hasPick(c)) p2PickOff[0]++;
        });
    Advancer ap1 = new Advancer(r1Pick);

    for (Object key : r2Drop) {
      if (key instanceof Integer) {
        int p2Mid = (Integer) key - p2DropOff[0];
        ReadCursor nextPick2 = ap2.adv(p2Mid);
        int raw = p2Mid + p2PickOff[0];
        ReadCursor nextPick1 = ap1.adv(raw);
        p2DropOff[0] += scanOp2Drop(nextPick1, nextPick2, r2Drop, pickSlot1, removed1) ? 1 : 0;
      } else {
        ReadCursor nextPick2 = ap2.adv(key);
        ReadCursor nextPick1 = ap1.adv(key);
        scanOp2Drop(nextPick1, nextPick2, r2Drop, pickSlot1, removed1);
      }
    }

    ap2.end();
    ap1.end();
    return droppedHere;
  }

  // ------------------------------------------------------------ writeOp1Pick

  /** op1's pick phase, transformed by op2 and written into {@code w}. */
  private void writeOp1Pick(ReadCursor r1Pick, ReadCursor r2Pick, ReadCursor r2Drop,
                            WriteCursor w, ReadCursor removed2) {
    boolean iAmMoved = false;

    Map<String, Object> c2p = compOf(r2Pick);
    if (OpComponent.hasPick(c2p)) {
      Integer slot2 = OpComponent.pk(c2p);
      if (slot2 != null) {
        // op2 moved whatever we're looking at. Take our picks from its destination instead.
        r2Drop = Json1.at(heldOp2DropByOp2, slot2);
        WriteCursor nw = new WriteCursor();
        Json1.put(heldPickWrites, slot2, nw);
        w = nw;
        iAmMoved = true;
        removed2 = null;
      } else { // op2 removed it
        r2Drop = null;
        removed2 = r2Pick.clone();
      }
    } else if (OpComponent.hasDrop(compOf(r2Drop))) {
      r2Drop = null;
    }

    Map<String, Object> c1 = r1Pick.getComponent();
    if (c1 != null) {
      Integer slot1 = OpComponent.pk(c1);
      if (slot1 != null) {
        if (removed2 != null) Json1.put(heldOp2RmForOp1, slot1, removed2);

        if (removed2 != null || (side == Side.RIGHT && iAmMoved)) {
          Json1.put(pickComponents, slot1, null);
        } else {
          Json1.put(pickComponents, slot1, w.getComponent());
        }
        Json1.put(heldOp1PickByOp1, slot1, r1Pick.clone());
        if (r2Drop != null) Json1.put(heldOp2DropByOp1, slot1, r2Drop.clone());
      } else if (c1.containsKey("r")) {
        if (removed2 == null) w.write("r", true);
        if (removed2 != null || iAmMoved) {
          if (cancelledRemoves == null) cancelledRemoves = new HashSet<>();
          cancelledRemoves.add(c1);
        }
      }
    }

    final int[] p2PickOff = {0}, p2DropOff = {0};
    Advancer ap2Pick = new Advancer(r2Pick, null, (k, c) -> {
      if (OpComponent.hasPick(c)) p2PickOff[0]++;
    });
    Advancer ap2Drop = new Advancer(r2Drop,
        (k, c) -> OpComponent.hasDrop(c) ? ~(k - p2DropOff[0]) : k - p2DropOff[0],
        (k, c) -> {
          if (OpComponent.hasDrop(c)) p2DropOff[0]++;
        });

    if (r1Pick != null) {
      for (Object key : r1Pick) {
        if (key instanceof String) {
          ReadCursor nextPick2 = ap2Pick.adv(key);
          ReadCursor nextDrop2 = ap2Drop.adv(key);
          w.descend(key);
          writeOp1Pick(r1Pick, nextPick2, nextDrop2, w, removed2);
          w.ascend();
        } else {
          ReadCursor nextPick2 = ap2Pick.adv(key);
          int p2Mid = (Integer) key - p2PickOff[0];
          ReadCursor nextDrop2 = OpComponent.hasPick(compOf(nextPick2)) ? null : ap2Drop.adv(p2Mid);
          int finalKey = p2Mid + p2DropOff[0];
          if (finalKey < 0) throw new AssertionError("trying to descend to a negative index");
          w.descend(finalKey);
          writeOp1Pick(r1Pick, nextPick2, nextDrop2, w, removed2);
          w.ascend();
        }
      }
    }

    ap2Pick.end();
    ap2Drop.end();
  }

  // ------------------------------------------------------------ writeOp1Drop

  /**
   * op1's drop phase. This is the hard part: the drop positions inside op1 have already been
   * adjusted by op1's own pick phase, so we have to mentally undo that, transform through op2,
   * then re-apply it. Returns true if we dropped something here.
   */
  private boolean writeOp1Drop(ReadCursor p1Pick, ReadCursor p1Drop, ReadCursor p2Pick,
                               ReadCursor p2Drop, WriteCursor w, ReadCursor removed2) {
    if (p1Drop == null) throw new AssertionError("xf called with no drop cursor");

    Map<String, Object> c1d = p1Drop.getComponent();
    Map<String, Object> c2d = compOf(p2Drop);
    boolean droppedHere = false;

    if (OpComponent.hasDrop(c1d)) {
      Integer slot1 = OpComponent.dk(c1d);
      if (slot1 != null) Json1.put(heldOp1DropByOp1, slot1, p1Drop.clone());

      // null if op2 removed the thing we moved, or if our move was cancelled because we're right.
      Map<String, Object> pc = slot1 != null ? Json1.at(pickComponents, slot1) : null;
      boolean identical = false;

      if (c1d.containsKey("i") || (slot1 != null && pc != null)) {
        Integer slot2 = null;
        if (c2d != null && (c2d.containsKey("i")
            || ((slot2 = OpComponent.dk(c2d)) != null && Json1.at(cancelledOp2, slot2) == null))) {
          identical = slot2 != null
              ? (slot1 != null && slot1.equals(Json1.at(op1PickAtOp2Pick, slot2)))
              : Json.deepEqual(c2d.get("i"), c1d.get("i"));

          // If we're the left op we can dodge the collision by moving their content elsewhere.
          if (!identical && (slot2 == null || side == Side.RIGHT
              || Json1.at(op1PickAtOp2Pick, slot2) == null)) {
            if (conflict == null) {
              conflict = new Conflict(ConflictType.DROP_COLLISION,
                  insOrMv(slot1 != null ? Json1.at(heldOp1PickByOp1, slot1) : null, p1Drop, c1d),
                  insOrMv(slot2 != null ? Json1.at(heldOp2PickByOp2, slot2) : null, p2Drop, c2d));
            }
          }
        }

        if (!identical) {
          if (removed2 != null) {
            if (conflict == null) {
              conflict = new Conflict(ConflictType.RM_UNEXPECTED_CONTENT,
                  insOrMv(slot1 != null ? Json1.at(heldOp1PickByOp1, slot1) : null, p1Drop, c1d),
                  Json1.INSTANCE.removeOp(removed2.getPath()));
            }
          } else {
            if (slot1 != null) {
              int newSlot = nextSlot++;
              Json1.put(outputSlotMap, newSlot, slot1);
              w.write("d", newSlot);
              pc.put("p", newSlot);
            } else {
              w.write("i", Json.deepClone(c1d.get("i")));
            }
            droppedHere = true;
          }
        }
      } else if (slot1 != null && pc == null) {
        ReadCursor h = Json1.at(heldOp2RmForOp1, slot1);
        if (h != null) removed2 = h.clone();
      }

      if (slot1 != null) {
        p1Pick = Json1.at(heldOp1PickByOp1, slot1);
        p2Pick = Json1.at(heldOp2PickByOp1, slot1);
        p2Drop = Json1.at(heldOp2DropByOp1, slot1);
      } else if (c1d.containsKey("i")) {
        // Descending into something we just inserted. Nobody else can be talking to us here.
        p1Pick = p2Pick = null;
        if (!identical) p2Drop = null;
      }
    } else if (OpComponent.hasPick(compOf(p1Pick))) {
      p1Pick = p2Pick = p2Drop = null;
    }

    Map<String, Object> c1p = compOf(p1Pick);
    Map<String, Object> c2p = compOf(p2Pick);

    if (OpComponent.hasPick(c2p)) {
      Integer slot2 = OpComponent.pk(c2p);
      if ((c2p.containsKey("r") && (c1p == null || !c1p.containsKey("r")))
          || Json1.at(cancelledOp2, slot2) != null) {
        // op2 moved content into somewhere op1 deleted.
        p2Drop = null;
        removed2 = p2Pick.clone();
      } else if (slot2 != null) {
        p2Drop = Json1.at(heldOp2DropByOp2, slot2);
        if (side == Side.RIGHT || Json1.at(op1PickAtOp2Pick, slot2) == null) {
          WriteCursor existing = Json1.at(heldDropWrites, slot2);
          if (existing == null) {
            existing = new WriteCursor();
            Json1.put(heldDropWrites, slot2, existing);
          }
          w = existing;
          w.reset();
          removed2 = null;
        }
      }
    } else if (!OpComponent.hasDrop(c1d) && OpComponent.hasDrop(c2d)) {
      p2Drop = null;
    }

    // Read their edit from wherever we ended up after all that teleporting.
    c2d = p2Drop != null ? p2Drop.getComponent() : null;

    Subtype t1 = getEditType(c1d);
    if (t1 != null) {
      Object e1 = Json1.getEdit(c1d);
      if (removed2 == null) {
        Subtype t2 = getEditType(c2d);
        Object e;
        if (t2 != null) {
          if (t1 != t2) throw new IllegalArgumentException("Transforming incompatible types");
          e = t1.transform(e1, Json1.getEdit(c2d), side);
        } else {
          e = Json.deepClone(e1);
        }
        Json1.writeEdit(w, t1, e);
      } else {
        if (conflict == null) {
          conflict = new Conflict(ConflictType.RM_UNEXPECTED_CONTENT,
              Json1.INSTANCE.editOp(p1Drop.getPath(), t1, e1, true),
              Json1.INSTANCE.removeOp(removed2.getPath()));
        }
      }
    }

    // Now the fun part: walking op1's children, keeping every conversion's index adjustments
    // straight as we go.
    final int[] p1PickOff = {0}, p1DropOff = {0};
    final int[] p2PickOff = {0}, p2DropOff = {0};
    final int[] outPickOff = {0}, outDropOff = {0};

    boolean p1pValid = p1Pick != null && p1Pick.descendFirst();
    boolean p1pDidDescend = p1pValid;
    boolean p2dValid = p2Drop != null && p2Drop.descendFirst();
    boolean p2dDidDescend = p2dValid;

    Advancer ap2p = new Advancer(p2Pick, null, (k, c) -> {
      if (OpComponent.hasPick(c)) p2PickOff[0]++;
    });

    for (Object key : p1Drop) {
      if (key instanceof Integer) {
        boolean hd1 = OpComponent.hasDrop(p1Drop.getComponent());
        int k1Mid = (Integer) key - p1DropOff[0];

        ReadCursor nextP1Pick;
        int p1k = 0;
        boolean valid = p1pValid;
        while (valid && p1Pick != null && p1Pick.getKey() instanceof Integer) {
          p1k = (Integer) p1Pick.getKey() + p1PickOff[0];
          Map<String, Object> c = p1Pick.getComponent();
          boolean hp = OpComponent.hasPick(c);
          if (p1k > k1Mid || (p1k == k1Mid && (!hp || (side == Side.LEFT && hd1)))) break;

          if (hp) {
            p1PickOff[0]--;
            Integer slot1 = OpComponent.pk(c);
            if ((c.containsKey("r") && (cancelledRemoves == null || !cancelledRemoves.contains(c)))
                || (slot1 != null && Json1.at(pickComponents, slot1) != null
                    && (side == Side.RIGHT || !Json1.containsValue(op1PickAtOp2Pick, slot1)))) {
              outPickOff[0]--;
            }
          }
          valid = p1Pick.nextSibling();
        }
        p1pValid = valid;
        nextP1Pick = (p1pValid && p1k == k1Mid && p1Pick != null) ? p1Pick : null;

        int raw = k1Mid - p1PickOff[0];
        ReadCursor nextP2Pick = ap2p.adv(raw);
        int k2Mid = raw - p2PickOff[0];

        ReadCursor nextP2Drop = null;
        {
          int op2Mid = 0;
          while (p2dValid && p2Drop.getKey() instanceof Integer) {
            int p2dk = (Integer) p2Drop.getKey();
            op2Mid = p2dk - p2DropOff[0];
            Map<String, Object> c = p2Drop.getComponent();
            boolean hd2 = OpComponent.hasDrop(c);

            if (op2Mid > k2Mid) break;
            if (op2Mid == k2Mid) {
              if (hd2) {
                if (side == Side.LEFT && hd1) {
                  nextP2Drop = p2Drop;
                  break;
                }
                boolean hp2 = nextP2Pick != null && OpComponent.hasPick(nextP2Pick.getComponent());
                if (side == Side.LEFT && hp2) break;
              } else {
                nextP2Drop = p2Drop;
                break;
              }
            }

            if (hd2) {
              Integer slot2 = OpComponent.dk(c);
              if (c.containsKey("i")
                  || (Json1.at(cancelledOp2, slot2) == null
                      && (Json1.at(op1PickAtOp2Pick, slot2) == null || side == Side.RIGHT))) {
                p2DropOff[0]++;
              } else if (Json1.at(cancelledOp2, slot2) != null
                  || (Json1.at(op1PickAtOp2Pick, slot2) != null && side == Side.LEFT)) {
                p2DropOff[0]++;
                outDropOff[0]--;
              }
            }
            p2dValid = p2Drop.nextSibling();
          }
        }

        int k2Final = k2Mid + p2DropOff[0];
        int descendKey = k2Final + outPickOff[0] + outDropOff[0];
        if (descendKey < 0) throw new AssertionError("trying to descend to a negative index");

        w.descend(descendKey);

        if (hd1) {
          // op1 is inserting a new item here. op2 can't know about it, so there's nothing to
          // transform against.
          nextP1Pick = nextP2Pick = nextP2Drop = null;
          p1DropOff[0]++;
        }

        if (writeOp1Drop(nextP1Pick, p1Drop, nextP2Pick, nextP2Drop, w, removed2)) outDropOff[0]++;
        w.ascend();

      } else { // descending into an object
        Object p1k = null;
        while (p1pValid) {
          p1k = p1Pick.getKey();
          if (p1k instanceof String && (Json.compareKeys(p1k, key) > 0 || p1k.equals(key))) break;
          p1pValid = p1Pick.nextSibling();
        }
        ReadCursor nextP1Pick = (p1pValid && key.equals(p1k)) ? p1Pick : null;
        ReadCursor nextP2Pick = ap2p.adv(key);

        Object p2dk = null;
        while (p2dValid) {
          p2dk = p2Drop.getKey();
          if (p2dk instanceof String && (Json.compareKeys(p2dk, key) > 0 || p2dk.equals(key))) break;
          p2dValid = p2Drop.nextSibling();
        }
        ReadCursor nextP2Drop = (p2dValid && key.equals(p2dk)) ? p2Drop : null;

        w.descend(key);
        writeOp1Drop(nextP1Pick, p1Drop, nextP2Pick, nextP2Drop, w, removed2);
        w.ascend();
      }
    }

    ap2p.end();
    if (p1pDidDescend && p1Pick != null) p1Pick.ascend();
    if (p2dDidDescend && p2Drop != null) p2Drop.ascend();

    return droppedHere;
  }

  // -------------------------------------------------------- writeHeldOp2Drop

  /** Copy over anything of op1's which op2 moved, into op2's (transformed) drop location. */
  private void writeHeldOp2Drop(ReadCursor p2Drop, ReadCursor outPick, ReadCursor outDrop,
                                WriteCursor w, Map<String, Object> parentC, boolean removedOut) {
    Map<String, Object> coutp = compOf(outPick);
    if (coutp != null && OpComponent.hasPick(coutp)) {
      if (OpComponent.pk(coutp) != null) {
        int slot = OpComponent.pk(coutp);
        parentC = coutp;
        outDrop = Json1.at(heldOutDropRead, slot);
        WriteCursor nw = new WriteCursor();
        Json1.put(heldOutDropWrites, slot, nw);
        w = nw;
      } else if (coutp.containsKey("r")) {
        outDrop = null;
        removedOut = true;
      }
    } else if (OpComponent.hasDrop(compOf(outDrop))) {
      outDrop = null;
    }

    Map<String, Object> c2 = p2Drop.getComponent();
    if (c2 != null) {
      Integer slot2 = OpComponent.dk(c2);
      if (slot2 != null) {
        WriteCursor hw = Json1.at(heldDropWrites, slot2);
        if (hw != null) {
          w.mergeTree(hw.get());
          outDrop = new ReadCursor(hw.get());
        }
      }
    }

    final int[] outPickOff = {0}, outDropOff = {0};
    Advancer oPickAdv = new Advancer(outPick, null, (k, c) -> {
      if (OpComponent.hasPick(c)) outPickOff[0]--;
    });
    Advancer oDropAdv = new Advancer(outDrop,
        (k, c) -> OpComponent.hasDrop(c) ? -(k - outDropOff[0]) - 1 : k - outDropOff[0],
        (k, c) -> {
          if (OpComponent.hasDrop(c)) outDropOff[0]++;
        });

    for (Object o2dk : p2Drop) {
      if (o2dk instanceof Integer) {
        ReadCursor nextOutPick = oPickAdv.adv(o2dk);
        int rmid = (Integer) o2dk + outPickOff[0];
        ReadCursor nextOutDrop = oDropAdv.adv(rmid);
        int rfinal = rmid + outDropOff[0];
        w.descend(rfinal);
        writeHeldOp2Drop(p2Drop, nextOutPick, nextOutDrop, w, parentC, removedOut);
        w.ascend();
      } else {
        w.descend(o2dk);
        writeHeldOp2Drop(p2Drop, oPickAdv.adv(o2dk), oDropAdv.adv(o2dk), w, parentC, removedOut);
        w.ascend();
      }
    }

    oPickAdv.end();
    oDropAdv.end();
  }

  private Conflict buildBlackholeConflict(final List<Object> heldOutDropContent) {
    WriteCursor w1 = new WriteCursor(), w2 = new WriteCursor();
    final int[] nextSlot1 = {0}, nextSlot2 = {0};

    for (int slotOut = 0; slotOut < heldOutDropContent.size(); slotOut++) {
      Object data = heldOutDropContent.get(slotOut);
      if (data == null) continue;
      eachDrop(new ReadCursor(Json.asList(data)), null, (dropSlot, r, wc) -> {
        int slot1 = Json1.at(outputSlotMap, dropSlot);
        w1.writeMove(Json1.at(heldOp1PickByOp1, slot1).getPath(),
            Json1.at(heldOp1DropByOp1, slot1).getPath(), nextSlot1[0]++);

        List<Integer> slot2s = Json1.at(op1PicksOp2DropSlots, slot1);
        if (slot2s != null) {
          for (Integer slot2 : slot2s) {
            if (Json1.at(cancelledOp2, slot2) == null
                && (side == Side.RIGHT || Json1.at(op1PickAtOp2Pick, slot2) == null)) {
              w2.writeMove(Json1.at(heldOp2PickByOp2, slot2).getPath(),
                  Json1.at(heldOp2DropByOp2, slot2).getPath(), nextSlot2[0]++);
            }
          }
        }
      });
    }

    return new Conflict(ConflictType.BLACKHOLE, w1.get(), w2.get());
  }

  // ---------------------------------------------------------------- helpers

  private interface DropVisitor {
    void visit(int slot, ReadCursor r, WriteCursor w);
  }

  private void eachDrop(ReadCursor r, WriteCursor w, DropVisitor fn) {
    r.traverse(w, (c, wl) -> {
      Integer slot = OpComponent.dk(c);
      if (slot != null) fn.visit(slot, r, wl);
    });
  }

  /** The offending part of an insert or move, for conflict reporting. */
  private List<Object> insOrMv(ReadCursor from, ReadCursor to, Map<String, Object> c) {
    return from != null
        ? Json1.INSTANCE.moveOp(from.getPath(), to.getPath())
        : Json1.INSTANCE.insertOp(to.getPath(), c.get("i"));
  }

  private static Map<String, Object> compOf(ReadCursor r) { return r == null ? null : r.getComponent(); }

  private static Subtype getEditType(Map<String, Object> c) { return Json1.getEditType(c); }
}
