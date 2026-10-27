package com.ottypes.json1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import com.ottypes.json1.Subtype.Side;

/**
 * Tests ported from the reference implementation's test suite, plus a small fuzzer which checks
 * the transformation property (TP1).
 */
public class Json1Test {

  private final Json1 json1 = Json1.INSTANCE;

  // ------------------------------------------------------------- plumbing

  static List<Object> op(String json) {
    return Json1.asOp(Json.parse(json));
  }

  static Object doc(String json) {
    return Json.parse(json);
  }

  static void assertDoc(String expectedJson, Object actual) {
    if (expectedJson == null) {
      assertNull("expected no document, got " + Json.stringify(actual), actual);
      return;
    }
    Object expected = Json.parse(expectedJson);
    assertTrue("expected " + Json.stringify(expected) + " but got " + Json.stringify(actual),
        Json.deepEqual(expected, actual));
  }

  static void assertOp(Object expectedOp, Object actualOp) {
    assertTrue("expected " + Json.stringify(expectedOp) + " but got " + Json.stringify(actualOp),
        Json.deepEqual(expectedOp, actualOp));
  }

  static void apply(String docJson, List<Object> op, String expectedJson) {
    assertDoc(expectedJson, Json1.INSTANCE.apply(doc(docJson), op));
  }

  /** Checks that op1 then transform(op2, op1, side) gives the same document as the other way. */
  static void xf(Object docJson, List<Object> op1, List<Object> op2, Side side,
                         Object expectedOp) {
    Json1.TransformResult res = Json1.INSTANCE.tryTransform(op1, op2, side);
    assertTrue("Transform failed: " + res.conflict, res.ok);
    assertOp(expectedOp, res.result);
  }

  // -------------------------------------------------------- checkValidOp

  @Test public void validationAcceptsGoodOps() {
    json1.checkValidOp(null);
    json1.checkValidOp(op("[{i:'hi'}]"));
    json1.checkValidOp(op("[['x',{p:0}],['y',{d:0}]]"));
    json1.checkValidOp(op("[1,2,3,{r:true}]"));
    json1.checkValidOp(op("[['x',{i:{a:1}}],['y',{i:'hi'}]]"));
  }

  @Test public void validationRejectsBadOps() {
    expectInvalid("[[{p:0}],[{d:0}]]");        // missing key in descend
    expectInvalid("[['x',{p:0}]]");            // pick with no drop
    expectInvalid("[['y',{d:0}]]");            // drop with no pick
    expectInvalid("[['x',{p:0},'y',{p:0}]]");  // duplicate pick slot
    expectInvalid("[[1,2,{p:0}],[1,2,{d:0}]]");// duplicate keys in descent
    expectInvalid("['x','x']");                // trailing scalar, no edit
    expectInvalid("[{}]");                     // empty component
  }

  private void expectInvalid(String json) {
    try {
      json1.checkValidOp(op(json));
      fail("expected " + json + " to be invalid");
    } catch (RuntimeException | AssertionError e) {
      assertTrue(true);
    }
  }

  // ------------------------------------------------------------ normalize

  @Test public void normalizesNoops() {
    assertNull(json1.normalize(null));
    assertNull(json1.normalize(Arrays.asList()));
  }

  @Test public void normalizesSimpleOps() {
    assertOp(op("[{i:'hi'}]"), json1.normalize(op("[{i:'hi'},1,2,3]")));
    assertOp(op("[1,2,3,{p:0,d:0}]"), json1.normalize(op("[[1,2,3,{p:0}],[1,2,3,{d:0}]]")));
    assertOp(op("[1,2,[3,{p:0}],[30,{d:0}]]"), json1.normalize(op("[[1,2,3,{p:0}],[1,2,30,{d:0}]]")));
    assertOp(op("[1,2,[3,{d:0}],[30,{p:0}]]"), json1.normalize(op("[[1,2,30,{p:0}],[1,2,3,{d:0}]]")));
  }

  @Test public void letsYouInsertNull() {
    assertOp(op("[{i:null}]"), json1.normalize(op("[{i:null}]")));
  }

  @Test public void normalizesEmbeddedOps() {
    assertOp(op("[{es:['hi']}]"), json1.normalize(op("[{es:[0,'hi']}]")));
    assertOp(op("[{es:['hi']}]"), json1.normalize(op("[{et:'text-unicode',e:['hi']}]")));
    assertOp(op("[{ena:5}]"), json1.normalize(op("[{et:'number',e:5}]")));
    assertOp(op("[{ena:5}]"), json1.normalize(op("[{ena:5}]")));
  }

  @Test public void removesEmbeddedNoops() {
    assertNull(json1.normalize(op("[{es:[]}]")));
    assertOp(op("['y',{r:true}]"), json1.normalize(op("[['x',{es:[]}],['y',{r:true}]]")));
  }

  @Test public void correctsPickAndDropIds() {
    assertOp(op("[['x',{p:0}],['y',{d:0}]]"), json1.normalize(op("[['x',{p:1}],['y',{d:1}]]")));
  }

  // ---------------------------------------------------------------- apply

  @Test public void setsProperties() {
    apply("[]", op("[0,{i:17}]"), "[17]");
    apply("{}", op("['x',{i:5}]"), "{'x':5}");
  }

  @Test public void editsRoot() {
    assertNull(json1.apply(doc("{'x':5}"), op("[{r:true}]")));
    assertNull(json1.apply(doc("''"), op("[{r:true}]")));
    assertNull(json1.apply(doc("'hi'"), op("[{r:true,i:null}]")));
    apply("'hi'", op("[{es:[2,' there']}]"), "'hi there'");
    apply("{'x':5}", op("[{r:true,i:[1,2,3]}]"), "[1,2,3]");
  }

  @Test public void moves() {
    apply("{'x':5}", op("[['x',{p:0}],['y',{d:0}]]"), "{'y':5}");
    apply("[0,1,2]", op("[[1,{p:0}],[2,{d:0}]]"), "[0,2,1]");
  }

  @Test public void complexListIndexStuff() {
    apply("[0,1,2,3,4,5]", op("[[1,{r:{},i:11}],[2,{r:{},i:12}]]"), "[0,11,12,3,4,5]");
  }

  @Test public void interspersedDescentAndEdits() {
    apply("{'x':{'y':{'was':'y'},'was':'x'}}",
        op("[['X',{d:0},'Y',{d:1}],['x',{p:0},'y',{p:1}]]"),
        "{'X':{'Y':{'was':'y'},'was':'x'}}");
  }

  @Test public void editsStringsAndNumbers() {
    apply("'errd'", op("[{es:[2,'maghe']}]"), "'ermagherd'");
    apply("5", op("[{ena:10}]"), "15");
    apply("5.5", op("[{ena:2}]"), "7.5");
  }

  @Test public void applyWithNullOpReturnsSnapshot() {
    assertDoc("{'x':5}", json1.apply(doc("{'x':5}"), null));
  }

  @Test public void doesNotMutateTheSnapshot() {
    Object snapshot = doc("{'x':{'y':1},'z':[1,2]}");
    json1.apply(snapshot, op("[['x',{p:0}],['y',{d:0}]]"));
    assertDoc("{'x':{'y':1},'z':[1,2]}", snapshot);
  }

  // ----------------------------------------------------- transformPosition

  @Test public void pathUnaffectedByUnrelatedOps() {
    path(Arrays.asList("a", "b", "c"), null, Arrays.asList("a", "b", "c"));
    path(Arrays.asList("a", "b", "c"), op("['x',{i:5}]"), Arrays.asList("a", "b", "c"));
    path(Arrays.asList("a", "b", "c"), op("['x',{r:true}]"), Arrays.asList("a", "b", "c"));
    path(Arrays.asList("a", "b", "c"), op("[['x',{p:0}],['y',{d:0}]]"), Arrays.asList("a", "b", "c"));
    path(Arrays.asList(1, 2, 3), op("[2,{i:5}]"), Arrays.asList(1, 2, 3));
    path(Arrays.asList(1, 2, 3), op("[1,2,4,{i:5}]"), Arrays.asList(1, 2, 3));
    path(Arrays.asList(1), op("[1,2,{r:true}]"), Arrays.asList(1));
    path(Arrays.asList("x"), op("['x','y',{r:true}]"), Arrays.asList("x"));
  }

  @Test public void pathAdjustsListIndicies() {
    path(Arrays.asList(2), op("[1,{i:5}]"), Arrays.asList(3));
    path(Arrays.asList(2), op("[2,{i:5}]"), Arrays.asList(3));
    path(Arrays.asList(2), op("[1,{r:true}]"), Arrays.asList(1));
    path(Arrays.asList(2), op("[[1,{p:0}],[3,{d:0}]]"), Arrays.asList(1));
    path(Arrays.asList(2), op("[[1,{d:0}],[3,{p:0}]]"), Arrays.asList(3));
    path(Arrays.asList(2), op("[[2,{d:0}],[3,{p:0}]]"), Arrays.asList(3));
  }

  @Test public void pathReturnsNullWhenRemoved() {
    path(Arrays.asList("x"), op("[{r:true}]"), null);
    path(Arrays.asList("x"), op("['x',{r:true}]"), null);
    path(Arrays.asList(1), op("[{r:true}]"), null);
    path(Arrays.asList(1), op("[1,{r:true}]"), null);
  }

  @Test public void pathFollowsMoves() {
    path(Arrays.asList("a", "z"), op("[['a',{p:0}],['y',{d:0}]]"), Arrays.asList("y", "z"));
    path(Arrays.asList("a", "b"), op("[['a','b',{p:0}],['z',{d:0}]]"), Arrays.asList("z"));
    path(Arrays.asList(1, 2), op("[[1,{p:0}],[10,{d:0}]]"), Arrays.asList(10, 2));
    path(Arrays.asList(1, 2), op("[[1,2,{p:0}],[10,{d:0}]]"), Arrays.asList(10));
    path(Arrays.asList(1, 2), op("[1,[1,{d:0}],[2,{p:0}]]"), Arrays.asList(1, 1));
  }

  @Test public void pathHandlesPickParentAndMove() {
    path(Arrays.asList("a", "b", "c"),
        op("[['a',{r:true},'b',{p:0}],['x',{d:0}]]"), Arrays.asList("x", "c"));
  }

  @Test public void pathAdjustsIndiciesUnderAPick() {
    path(Arrays.asList("a", "b", 10),
        op("[['a',{p:0},'b',1,{r:true}],['x',{d:0}]]"), Arrays.asList("x", "b", 9));
  }

  @Test public void pathUsesEmbeddedStringTransformPosition() {
    path(Arrays.asList("x", "y", "z", 1), op("['x','y','z',{es:['abc']}]"), Arrays.asList("x", "y", "z", 4));
    // An emoji counts as a single position: text-unicode counts code points, not UTF-16 units.
    path(Arrays.asList("x", "y", "z", 1), op("['x','y','z',{es:['\uD83D\uDC83']}]"),
        Arrays.asList("x", "y", "z", 2));
    path(Arrays.asList("x", "y", "z"), op("['x','y','z',{es:['hi']}]"), Arrays.asList("x", "y", "z"));
  }

  @Test public void pathWithPicksAndDrops() {
    path(Arrays.asList("na", 0, "nb", 0),
        op("[{r:true,d:0},['na',0,{p:0}],['nb',0,{i:{'object':'four'}}]]"),
        Arrays.asList("nb", 1));
  }

  private void path(List<Object> input, List<Object> op, List<Object> expected) {
    List<Object> result = json1.transformPosition(input, op);
    assertOp(expected, result);
  }

  // -------------------------------------------------------------- compose

  @Test public void composesEmptyOpsToNothing() {
    assertNull(json1.compose(null, null));
  }

  @Test public void composeOp1Drop() {
    assertOp(op("['x',{r:true}]"), json1.compose(op("[['x',{p:0}],['y',{d:0}]]"), op("['y',{r:true}]")));
    assertOp(op("[['x',{r:true}],['y',{r:true}]]"),
        json1.compose(op("[['x',{p:0}],['y',0,{d:0}]]"), op("['y',{r:true}]")));
    assertOp(op("[['x',{p:0},'a',{r:true}],['y',{d:0}]]"),
        json1.compose(op("[['x',{p:0}],['y',{d:0}]]"), op("['y','a',{r:true}]")));
    assertOp(op("[['x',{r:true},'a',{p:0}],['z',{d:0}]]"),
        json1.compose(op("[['x',{p:0}],['y',{d:0}]]"),
            op("[['y',{r:true},'a',{p:0}],['z',{d:0}]]")));
    assertOp(op("[['x',{p:0}],['z',{d:0}]]"),
        json1.compose(op("[['x',{p:0}],['y',{d:0}]]"), op("[['y',{p:0}],['z',{d:0}]]")));
    assertOp(op("[['x',{p:0}],['y',[0,{r:true}],[9,{d:0}]]]"),
        json1.compose(op("[['x',{p:0}],['y',10,{d:0}]]"), op("['y',0,{r:true}]")));
  }

  @Test public void composeOp1Insert() {
    assertNull(json1.compose(op("['x',{i:{'a':'hi'}}]"), op("['x',{r:true}]")));
    assertOp(op("['x',{r:true}]"), json1.compose(op("['x',0,{i:{'a':'hi'}}]"), op("['x',{r:true}]")));
    assertOp(op("['x',{i:{'b':'woo'}}]"),
        json1.compose(op("['x',{i:{'a':'hi','b':'woo'}}]"), op("['x','a',{r:true}]")));
    assertOp(op("['y',{i:'hi'}]"), json1.compose(op("['x',{i:{'a':'hi','b':'woo'}}]"),
        op("[['x',{r:true},'a',{p:0}],['y',{d:0}]]")));
    assertOp(op("['x',{i:{}}]"), json1.compose(op("['x',{i:{}},'y',{i:'hi'}]"), op("['x','y',{r:true}]")));
    assertOp(op("['x',{i:{}},'y',{i:[1,3]}]"),
        json1.compose(op("['x',{i:{}},'y',{i:[1,2,3]}]"), op("['x','y',1,{r:true}]")));
    assertOp(op("[['x',{i:['a','b','c']}],['y',{i:'XX'}]]"),
        json1.compose(op("['x',{i:['a','b','c']},1,{i:'XX'}]"), op("[['x',1,{p:0}],['y',{d:0}]]")));
    assertOp(op("[['x',{i:['a','b']},1,{i:'XX'}],['y',{i:'c'}]]"),
        json1.compose(op("['x',{i:['a','b','c']},1,{i:'XX'}]"), op("[['x',3,{p:0}],['y',{d:0}]]")));
    assertOp(op("['z',{i:{}},'y',{i:[1,2,3]}]"),
        json1.compose(op("['x',{i:{}},'y',{i:[1,2,3]}]"), op("[['x',{p:0}],['z',{d:0}]]")));
  }

  @Test public void composedOpsApplyTheSameAsTheirParts() {
    Object snapshot = doc("{'x':{'y':'hi'},'z':[1,2,3]}");
    // Note the children have to be in sorted order: q before x.
    List<Object> op1 = op("[['q',{d:0}],['x',{p:0}]]");
    List<Object> op2 = op("['q','y',{es:[0,'XY']}]");

    Object step1 = json1.apply(snapshot, op1);
    Object expected = json1.apply(step1, op2);
    assertDoc(Json.stringify(expected), json1.apply(snapshot, json1.compose(op1, op2)));
  }

  // ---------------------------------------------------------------- invert

  @Test public void makeInvertible() {
    assertNull(json1.makeInvertible(null, null));
    assertOp(op("[{i:'hi'}]"), json1.makeInvertible(op("[{i:'hi'}]"), null));
    assertOp(op("[{r:'hi'}]"), json1.makeInvertible(op("[{r:true}]"), doc("'hi'")));
    assertOp(op("[2,{r:'hi'}]"), json1.makeInvertible(op("[2,{r:true}]"), doc("[0,1,'hi']")));
    assertOp(op("[['x',{r:'hi'}],['y',{i:5}]]"),
        json1.makeInvertible(op("[['x',{r:true}],['y',{i:5}]]"), doc("{'x':'hi'}")));
    assertOp(op("[{es:[1,{d:'bcdef'}]}]"),
        json1.makeInvertible(op("[{es:[1,{d:5}]}]"), doc("'abcdef'")));
  }

  @Test public void makeInvertibleSkipsChildRemoves() {
    assertOp(op("[{r:{'x':5}},'y',{r:6}]"),
        json1.makeInvertible(op("[{r:true},'y',{r:true}]"), doc("{'x':5,'y':6}")));
    assertOp(op("[{r:['a','c']},1,{r:'b'}]"),
        json1.makeInvertible(op("[{r:true},1,{r:true}]"), doc("['a','b','c']")));
  }

  @Test public void invertsChildOperations() {
    inv(op("[{es:['x']}]"), op("[{es:[{d:'x'}]}]"), doc("'_'"));
    inv(op("[2,{es:['x']}]"), op("[2,{es:[{d:'x'}]}]"), doc("[0,1,'_']"));
  }

  @Test public void invertSwapsPickAndDrop() {
    inv(op("[['x',{p:0}],['y',{d:0}]]"), op("[['x',{d:0}],['y',{p:0}]]"), doc("{'x':5}"));
    inv(op("[['a',{p:0}],['b',{p:1}],['c',{d:0}],['d',{d:1}]]"),
        op("[['a',{d:0}],['b',{d:1}],['c',{p:0}],['d',{p:1}]]"), doc("{'a':5,'b':6}"));
  }

  @Test public void invertTeleportsEmbeddedEdits() {
    inv(op("[['x',{p:0}],['y',{d:0},0,{es:['hi']}]]"),
        op("[['x',{d:0},0,{es:[{d:'hi'}]}],['y',{p:0}]]"), doc("{'x':['_']}"));
    inv(op("[[0,{r:true}],[1,{es:['hi']}]]"), op("[[0,{i:true}],[2,{es:[{d:'hi'}]}]]"),
        doc("[true,123,'_']"));
    inv(op("[[0,{i:true}],[1,{es:['hi']}]]"), op("[0,{r:true,es:[{d:'hi'}]}]"), doc("['_']"));
  }

  @Test public void invertRemovesImmediatelyEditedInserts() {
    // These all insert something and edit it in the same operation, so there's nothing left to
    // undo except the remove. Note the document starts out empty - inserting over the top of an
    // existing value needs a remove first.
    inv(op("[{i:'hi',es:['x']}]"), op("[{r:'xhi'}]"), null);
    inv(op("[{i:{'x':'hi'}},'x',{es:['x']}]"), op("[{r:{'x':'xhi'}}]"), null);
    inv(op("[['a',{i:'hi',es:['x']}],['b',{es:['y']}]]"),
        op("[['a',{r:'xhi'}],['b',{es:[{d:'y'}]}]]"), doc("{'b':'_'}"));
  }

  private void inv(List<Object> operation, List<Object> expectedInverse, Object snapshot) {
    List<Object> actual = json1.invert(operation);
    assertOp(expectedInverse, actual);

    // Applying an operation then its inverse should be a round trip.
    Object after = json1.apply(snapshot, operation);
    assertDoc(Json.stringify(snapshot), json1.apply(after, expectedInverse));

    // The inverse should already be in canonical form.
    assertOp(actual, json1.normalize(actual));
  }

  // ------------------------------------------------------------- transform

  @Test public void transformReadmeExample() {
    List<Object> op1 = json1.moveOp(Arrays.asList("a", "x"), Arrays.asList("a", "y"));
    List<Object> op2 = json1.compose(
        json1.moveOp(Arrays.asList("a"), Arrays.asList("b")),
        json1.insertOp(Arrays.asList("b", "z"), "hi there"));

    List<Object> op2AfterOp1 = json1.transform(op2, op1, Side.RIGHT);
    List<Object> op1AfterOp2 = json1.transform(op1, op2, Side.LEFT);

    Object snapshot = doc("{'a':{'x':5}}");
    Object left = json1.apply(json1.apply(snapshot, op1), op2AfterOp1);
    Object right = json1.apply(json1.apply(snapshot, op2), op1AfterOp2);

    assertDoc("{'b':{'y':5,'z':'hi there'}}", left);
    assertDoc(Json.stringify(left), right);
  }

  @Test public void transformOp1Pick() {
    xf("{'x':5}", op("[['x',{p:0}],['y',{d:0}]]"), op("['x',{r:true}]"), Side.LEFT, null);
    xf("{'x':{'a':5}}", op("[['x','a',{p:0}],['y',{d:0}]]"), op("['x',{r:true}]"), Side.LEFT, null);
    xf("{'x':{'a':5}}", op("['x',['a',{p:0}],['b',{d:0}]]"), op("['x',{r:true}]"), Side.LEFT, null);

    xf("{'x':5}", op("[['x',{p:0}],['z',{d:0}]]"), op("[['x',{p:0}],['y',{d:0}]]"), Side.LEFT,
        op("[['y',{p:0}],['z',{d:0}]]"));
    xf("{'x':5}", op("[['x',{p:0}],['z',{d:0}]]"), op("[['x',{p:0}],['y',{d:0}]]"), Side.RIGHT, null);

    xf("{'x':{'a':5}}", op("[['x','a',{p:0}],['z',{d:0}]]"), op("[['x',{p:0}],['y',{d:0}]]"), Side.LEFT,
        op("[['y','a',{p:0}],['z',{d:0}]]"));

    xf("{'x':5}", op("[['x',{p:0}],['z',{d:0}]]"), op("['x',{es:['hi']}]"), Side.LEFT,
        op("[['x',{p:0}],['z',{d:0}]]"));
  }

  @Test public void transformOp1Delete() {
    xf("{'x':5}", op("['x',{r:true}]"), op("['x',{r:true}]"), Side.LEFT, null);
    xf("{'x':{'a':5}}", op("['x','a',{r:true}]"), op("['x',{r:true}]"), Side.LEFT, null);
    xf("{'x':5,'y':2}", op("['x',{r:true}]"), op("['y',{r:true}]"), Side.LEFT, op("['x',{r:true}]"));
  }

  @Test public void transformOp1Edit() {
    // Concurrent edits to the same string get transformed by the embedded type.
    Object snapshot = doc("{'x':'abcdef'}");
    List<Object> op1 = op("['x',{es:[0,'AB']}]"); // -> ABabcdef
    List<Object> op2 = op("['x',{es:[3,'CD']}]"); // -> abcCDdef

    List<Object> op1_ = json1.transform(op1, op2, Side.LEFT);
    List<Object> op2_ = json1.transform(op2, op1, Side.RIGHT);
    assertNotNull(op1_);

    Object left = json1.apply(json1.apply(snapshot, op2), op1_);
    Object right = json1.apply(json1.apply(snapshot, op1), op2_);
    assertDoc("{'x':'ABabcCDdef1'}", left);
    assertDoc(Json.stringify(left), right);

    // Composing gives the same answer as applying the operations one after the other.
    assertDoc("{'x':'ABabcCDdef'}", json1.apply(snapshot, json1.compose(op1, op2_)));
    assertDoc("{'x':'ABabcCDdef'}", json1.apply(snapshot, json1.compose(op2, op1_)));
  }

  @Test public void transformNumberEdits() {
    Object snapshot = doc("{'x':1}");
    List<Object> op1 = op("['x',{ena:10}]");
    List<Object> op2 = op("['x',{ena:5}]");
    assertDoc("{'x':16}", json1.apply(json1.apply(snapshot, op1), json1.transform(op2, op1, Side.RIGHT)));
    assertDoc("{'x':16}", json1.apply(json1.apply(snapshot, op2), json1.transform(op1, op2, Side.LEFT)));
  }

  // --------------------------------------------------------------- conflicts

  @Test public void detectsDropCollision() {
    List<Object> op1 = op("[['a',{p:0}],['x',{d:0}]]");
    List<Object> op2 = op("[['b',{p:0}],['x',{d:0}]]");

    Json1.TransformResult left = json1.tryTransform(op1, op2, Side.LEFT);
    assertTrue("Expected a conflict, got " + left.result, !left.ok);
    assertEquals(ConflictType.DROP_COLLISION, left.conflict.type);
  }

  @Test public void detectsRemoveUnexpectedContent() {
    List<Object> op1 = op("['x',{r:true}]");
    List<Object> op2 = op("['x',{es:['hi']}]");

    Json1.TransformResult res = json1.tryTransform(op1, op2, Side.LEFT);
    assertTrue("Expected a conflict, got " + res.result, !res.ok);
    assertEquals(ConflictType.RM_UNEXPECTED_CONTENT, res.conflict.type);
  }

  @Test public void conflictsAreSymmetric() {
    List<Object> op1 = op("[['a',{p:0}],['x',{d:0}]]");
    List<Object> op2 = op("[['b',{p:0}],['x',{d:0}]]");

    Json1.TransformResult left = json1.tryTransform(op1, op2, Side.LEFT);
    Json1.TransformResult right = json1.tryTransform(op2, op1, Side.RIGHT);
    assertTrue(!left.ok && !right.ok);
    assertEquals(left.conflict.type, right.conflict.type);
  }

  @Test(expected = ConflictException.class)
  public void transformThrowsOnConflict() {
    json1.transform(op("['x',{r:true}]"), op("['x',{es:['hi']}]"), Side.LEFT);
  }

  @Test public void transformNoConflictConverges() {
    Object snapshot = doc("{'a':1,'b':2}");
    List<Object> op1 = op("['a',{es:['x']}]");      // op1 edits a
    List<Object> op2 = op("['a',{r:true}]");        // op2 removes a

    List<Object> r1 = json1.transformNoConflict(op2, op1, Side.RIGHT);
    List<Object> r2 = json1.transformNoConflict(op1, op2, Side.LEFT);

    Object left = json1.apply(json1.apply(snapshot, op1), r1);
    Object right = json1.apply(json1.apply(snapshot, op2), r2);
    assertDoc(Json.stringify(right), left);
  }

  // ------------------------------------------------------------------ fuzzer

  /**
   * Checks TP1: applying op1 then op2' must give the same document as op2 then op1'. Skips the
   * iteration if transforming produced a legitimate conflict.
   */
  @Test public void randomOpsConverge() {
    Random r = new Random(12345);
    int checked = 0, conflicts = 0;

    for (int iter = 0; iter < 6000; iter++) {
      Object snapshot = randomDoc(r, 0);

      List<Object> op1 = randomOp(snapshot, r);
      List<Object> op2 = randomOp(snapshot, r);
      if (op1 == null || op2 == null) continue;

      Json1.TransformResult t1 = safeTryTransform(op1, op2, Side.LEFT);
      Json1.TransformResult t2 = safeTryTransform(op2, op1, Side.RIGHT);
      if (!t1.ok || !t2.ok) { conflicts++; continue; }

      Object after1 = json1.apply(json1.apply(snapshot, op1), t2.result);
      Object after2 = json1.apply(json1.apply(snapshot, op2), t1.result);

      if (!Json.deepEqual(after1, after2)) {
        fail("Operations diverged on iteration " + iter + "\n"
            + "doc:   " + Json.stringify(snapshot) + "\n"
            + "op1:   " + Json.stringify(op1) + "\n"
            + "op2:   " + Json.stringify(op2) + "\n"
            + "op1':  " + Json.stringify(t1.result) + "\n"
            + "op2':  " + Json.stringify(t2.result) + "\n"
            + "left:  " + Json.stringify(after1) + "\n"
            + "right: " + Json.stringify(after2));
      }
      checked++;
    }

    assertTrue("fuzzer did not check enough operations (checked=" + checked
        + ", conflicts=" + conflicts + ")", checked > 200);
  }

  /** Even when operations conflict, resolving the conflict must leave both clients in step. */
  @Test public void randomConflictsResolve() {
    Random r = new Random(777);
    int resolved = 0;

    for (int iter = 0; iter < 3000; iter++) {
      Object snapshot = randomDoc(r, 0);
      List<Object> op1 = randomOp(snapshot, r);
      List<Object> op2 = randomOp(snapshot, r);
      if (op1 == null || op2 == null) continue;
      if (json1.tryTransform(op1, op2, Side.LEFT).ok) continue; // only conflicts here

      List<Object> op2AfterOp1 = json1.transformNoConflict(op2, op1, Side.RIGHT);
      List<Object> op1AfterOp2 = json1.transformNoConflict(op1, op2, Side.LEFT);

      Object left = json1.apply(json1.apply(snapshot, op1), op2AfterOp1);
      Object right = json1.apply(json1.apply(snapshot, op2), op1AfterOp2);
      if (!Json.deepEqual(left, right)) {
        fail("Conflict resolution diverged on iteration " + iter + "\n"
            + "doc:  " + Json.stringify(snapshot) + "\n"
            + "op1:  " + Json.stringify(op1) + "\n"
            + "op2:  " + Json.stringify(op2) + "\n"
            + "left:  " + Json.stringify(left) + "\n"
            + "right: " + Json.stringify(right));
      }
      resolved++;
    }
    assertTrue("fuzzer did not resolve enough conflicts", resolved > 20);
  }

  /** Inverting a random operation should always undo it exactly. */
  @Test public void randomOpsInvert() {
    Random r = new Random(999);
    int checked = 0;

    for (int iter = 0; iter < 2000; iter++) {
      Object snapshot = randomDoc(r, 0);
      List<Object> operation = randomOp(snapshot, r);
      if (operation == null) continue;

      Object after;
      try {
        after = json1.apply(snapshot, operation);
      } catch (Throwable e) {
        continue; // op wasn't valid for this document
      }

      List<Object> inverse = json1.invertWithDoc(operation, snapshot);
      Object back = json1.apply(after, inverse);
      if (!Json.deepEqual(snapshot, back)) {
        fail("Invert failed on iteration " + iter + "\n"
            + "doc:     " + Json.stringify(snapshot) + "\n"
            + "op:      " + Json.stringify(operation) + "\n"
            + "inverse: " + Json.stringify(inverse) + "\n"
            + "result:  " + Json.stringify(back));
      }
      checked++;
    }
    assertTrue("fuzzer did not check enough operations", checked > 100);
  }

  /** Composing random operation pairs must behave like applying them one at a time. */
  @Test public void randomOpsCompose() {
    Random r = new Random(2024);
    int checked = 0;

    for (int iter = 0; iter < 2000; iter++) {
      Object snapshot = randomDoc(r, 0);
      List<Object> op1 = randomOp(snapshot, r);
      if (op1 == null) continue;

      Object mid;
      try {
        mid = json1.apply(snapshot, op1);
      } catch (Throwable e) {
        continue;
      }
      List<Object> op2 = randomOp(mid, r);
      if (op2 == null) continue;

      Object expected;
      List<Object> composed;
      try {
        expected = json1.apply(mid, op2);
        composed = json1.compose(op1, op2);
      } catch (Throwable e) {
        continue;
      }

      Object actual = json1.apply(snapshot, composed);
      if (!Json.deepEqual(expected, actual)) {
        fail("Compose failed on iteration " + iter + "\n"
            + "doc:   " + Json.stringify(snapshot) + "\n"
            + "op1:   " + Json.stringify(op1) + "\n"
            + "op2:   " + Json.stringify(op2) + "\n"
            + "comp:  " + Json.stringify(composed) + "\n"
            + "want:  " + Json.stringify(expected) + "\n"
            + "got:   " + Json.stringify(actual));
      }
      checked++;
    }
    assertTrue("fuzzer did not check enough operations", checked > 100);
  }

  private Json1.TransformResult safeTryTransform(List<Object> op1, List<Object> op2, Side side) {
    try {
      return json1.tryTransform(op1, op2, side);
    } catch (RuntimeException e) {
      // A badly generated operation - treat like a conflict and move on.
      Json1.TransformResult res = new Json1.TransformResult(
          new Conflict(ConflictType.DROP_COLLISION, new ArrayList<>(), new ArrayList<>()));
      return res;
    }
  }

  // ------------------------------------------------------- op/doc generators

  static final String[] KEYS = {"a", "b", "c", "x", "y", "z"};

  static Object randomDoc(Random r, int depth) {
    switch (r.nextInt(depth > 2 ? 4 : 6)) {
      case 0:
        return r.nextInt(100);
      case 1:
        return randomString(r, 1 + r.nextInt(6));
      case 2:
        return r.nextBoolean();
      case 3:
        return null;
      case 4: {
        int n = r.nextInt(4);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(KEYS[r.nextInt(KEYS.length)], randomDoc(r, depth + 1));
        return m;
      }
      default: {
        int n = r.nextInt(4);
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(randomDoc(r, depth + 1));
        return l;
      }
    }
  }

  static String randomString(Random r, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) sb.append((char) ('a' + r.nextInt(20)));
    return sb.toString();
  }

  /** Build a random (usually valid) operation against the given document. */
  static List<Object> randomOp(Object root, Random r) {
    WriteCursor w = new WriteCursor();
    boolean wroteSomething = false;
    // Pick/drop slots have to count up from 0, so moves get their own counter.
    int[] nextSlot = {0};

    // Each part of the operation has to work on a path unrelated to the others - otherwise we'd
    // generate nonsense like editing inside something another part removed.
    List<List<Object>> used = new ArrayList<>();
    // Dropping into a list shifts the item which used to live there, so any other part of the
    // same operation would silently start talking about whatever we just inserted. Keep those
    // operations to a single part.
    boolean droppedIntoList = false;

    int parts = 1 + r.nextInt(3);
    for (int i = 0; i < parts; i++) {
      if (droppedIntoList) break;
      List<Object> path = randomExistingPath(root, r);
      if (path == null || overlaps(used, path)) continue;
      used.add(path);

      final int slot = nextSlot[0]++;
      int action = r.nextInt(6);
      try {
        switch (action) {
          case 0: { // move something into a spare slot at the root
            List<Object> from = path;
            if (from.isEmpty()) break;
            List<Object> to;
            // List indexes in the drop phase are relative to the list after this operation's own
            // picks, so the last slot is size()-1 rather than size().
            if (root instanceof List) {
              to = Arrays.asList((Object) Math.max(0, Json.asList(root).size() - 1));
            } else if (root instanceof Map) {
              to = Arrays.asList((Object) freshKey(r));
            } else break; // nowhere new to put it
            if (to.equals(from)) break;
            if (used.size() > 1 && to.get(0) instanceof Integer) break;
            w.at(from, wc -> wc.write("p", slot));
            w.at(to, wc -> wc.write("d", slot));
            if (to.get(0) instanceof Integer) droppedIntoList = true;
            wroteSomething = true;
            break;
          }
          case 1: { // remove
            if (path.isEmpty()) break;
            w.writeAtPath(path, "r", true);
            wroteSomething = true;
            break;
          }
          case 2: { // insert somewhere inside an existing container
            Object parent = valueAt(root, path);
            if (!(parent instanceof List) && !(parent instanceof Map)) break;
            Object newKey = parent instanceof List
                ? Json.asList(parent).size()   // lists only grow at the end
                : freshKey(r);
            w.writeAtPath(append(path, newKey), "i", randomDoc(r, 2));
            wroteSomething = true;
            break;
          }
          case 3: { // replace (remove + insert)
            if (path.isEmpty()) break;
            w.at(path, wc -> {
              wc.write("r", true);
              wc.write("i", randomDoc(r, 2));
            });
            wroteSomething = true;
            break;
          }
          case 4: { // edit a string
            Object value = valueAt(root, path);
            if (!(value instanceof String)) break;
            int pos = r.nextInt(((String) value).length() + 1);
            List<Object> edit = new ArrayList<>();
            edit.add(pos);
            edit.add(randomString(r, 1 + r.nextInt(3)));
            w.writeAtPath(path, "es", edit);
            wroteSomething = true;
            break;
          }
          default: { // edit a number
            Object value = valueAt(root, path);
            if (!(value instanceof Integer)) break;
            w.writeAtPath(path, "ena", 1 + r.nextInt(20));
            wroteSomething = true;
            break;
          }
        }
      } catch (Throwable e) {
        // Ignore: this generated operation would have been invalid for the document.
      }
    }

    if (!wroteSomething) return null;
    List<Object> result = w.get();
    try {
      Json1.INSTANCE.checkValidOp(result);
    } catch (RuntimeException e) {
      return null;
    }
    return result;
  }

  static String freshKey(Random r) {
    return "k" + r.nextInt(1000);
  }

  /**
   * True if {@code path} sits inside, contains, or is a sibling (inside a list) of any path
   * already used by this operation. Siblings within a list can't be mixed either: index numbers
   * in the drop phase are relative to the document <em>after</em> this operation's own picks, so
   * two operations inside the same list don't compose into a valid single operation.
   */
  static boolean overlaps(List<List<Object>> used, List<Object> path) {
    for (List<Object> u : used) {
      int shorter = Math.min(u.size(), path.size());
      boolean same = true;
      for (int i = 0; i < shorter; i++) {
        if (!u.get(i).equals(path.get(i))) { same = false; break; }
      }
      if (same) return true;
      if (u.size() == path.size() && u.size() > 0
          && u.subList(0, u.size() - 1).equals(path.subList(0, path.size() - 1))) {
        return true; // siblings inside the same container
      }
      // Two different list indexes inside one operation don't mix: picking one of them up
      // shifts everything after it before the later phases get to run.
      for (int i = 0; i < shorter; i++) {
        if (u.get(i) instanceof Integer && path.get(i) instanceof Integer
            && !u.get(i).equals(path.get(i))) return true;
      }
    }
    return false;
  }

  static List<Object> append(List<Object> path, Object key) {
    List<Object> out = new ArrayList<>(path);
    out.add(key);
    return out;
  }

  static Object valueAt(Object root, List<Object> path) {
    Object current = root;
    for (Object k : path) {
      if (current == null) return null;
      if (k instanceof Integer) {
        if (!(current instanceof List)) return null;
        List<Object> l = Json.asList(current);
        int i = (Integer) k;
        if (i >= l.size()) return null;
        current = l.get(i);
      } else {
        if (!(current instanceof Map)) return null;
        Map<String, Object> m = Json.asMap(current);
        if (!m.containsKey(k)) return null;
        current = m.get(k);
      }
    }
    return current;
  }

  /** Pick a random path to somewhere that exists in the document. */
  static List<Object> randomExistingPath(Object root, Random r) {
    List<Object> path = new ArrayList<>();
    Object current = root;

    boolean descended = true;
    while (descended) {
      descended = false;
      if (current instanceof Map) {
        Map<String, Object> m = Json.asMap(current);
        if (m.isEmpty()) break;
        List<String> keys = new ArrayList<>(m.keySet());
        String key = keys.get(r.nextInt(keys.size()));
        path.add(key);
        current = m.get(key);
        descended = true;
      } else if (current instanceof List) {
        List<Object> l = Json.asList(current);
        if (l.isEmpty()) break;
        int i = r.nextInt(l.size());
        path.add(i);
        current = l.get(i);
        descended = true;
      }
    }
    return path;
  }
}
