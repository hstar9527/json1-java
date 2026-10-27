package com.ottypes.json1;

import com.ottypes.json1.Subtype.Side;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Cases ported straight out of the reference implementation's test suite (test/test.js) which
 * aren't already covered by {@link Json1Test}: validation, normalize, apply, transformPosition,
 * makeInvertible, invert and compose.
 *
 * <p>Operation data is written in the relaxed JSON syntax the reference tests use (single quotes,
 * unquoted keys) and parsed by {@link Json#parse}.
 */
public class Json1SpecTest {

    private static final Json1 json1 = Json1.INSTANCE;

    @BeforeClass
    public static void registerSubtypes() {
        Json1.registerSubtype(new SimpleSubtype());
    }

    // ------------------------------------------------------------- plumbing

    private static List<Object> op(String json) {
        return Json1.asOp(Json.parse(json));
    }

    private static Object doc(String json) {
        return Json.parse(json);
    }

    private static void valid(String opJson) {
        json1.checkValidOp(op(opJson));
    }

    private static void invalid(String opJson) {
        try {
            json1.checkValidOp(op(opJson));
        } catch (RuntimeException | AssertionError e) {
            return;
        }
        fail("expected " + opJson + " to be invalid");
    }

    /**
     * The reference suite's n(): expected defaults to the input.
     */
    private static void n(String input) {
        n(input, input);
    }

    private static void n(String input, String expected) {
        assertOp("normalize", expected == null ? null : op(expected), json1.normalize(op(input)));
    }

    private static void apply(String docJson, String opJson, String expectedJson) {
        Object snapshot = doc(docJson);
        Object frozen = Json.deepClone(snapshot);
        Object result = json1.apply(snapshot, op(opJson));
        // Applying an operation must never mutate the snapshot it was given.
        assertTrue("snapshot was mutated", Json.deepEqual(frozen, snapshot));
        assertDoc(expectedJson, result);
    }

    private static void path(String pathJson, String opJson) {
        path(pathJson, opJson, pathJson);
    }

    private static void path(String pathJson, String opJson, String expectedJson) {
        List<Object> actual = json1.transformPosition(Json.asList(doc(pathJson)), op(opJson));
        assertOp("transformPosition", expectedJson == null ? null : doc(expectedJson), actual);
    }

    /**
     * The reference suite's mi(): expected defaults to the input operation.
     */
    private static void mi(String opJson) {
        mi(opJson, "null", opJson);
    }

    private static void mi(String opJson, String docJson, String expectedJson) {
        List<Object> result = json1.makeInvertible(op(opJson), doc(docJson));
        assertOp("makeInvertible", expectedJson == null ? null : op(expectedJson), result);
    }

    /**
     * The reference suite's i(): invert the op, then check applying the inverse undoes it.
     */
    private static void i(String opJson, String expectedJson) {
        i(opJson, expectedJson, "null");
    }

    private static void i(String opJson, String expectedJson, String docJson) {
        List<Object> op = op(opJson);
        List<Object> inverse = json1.invert(op);
        assertOp("invert", op(expectedJson), inverse);

        Object after = json1.apply(doc(docJson), op);
        Object back = json1.apply(after, inverse);
        assertDoc(docJson, back);

        assertOp("inverted op not canonical", inverse, json1.normalize(inverse));
    }

    private static void compose(String op1Json, String op2Json, String expectedJson) {
        assertOp("compose", expectedJson == null ? null : op(expectedJson),
                json1.compose(op(op1Json), op(op2Json)));
    }

    private static void assertOp(String what, Object expected, Object actual) {
        assertTrue(what + ": expected " + Json.stringify(expected) + " but got " + Json.stringify(actual),
                Json.deepEqual(expected, actual));
    }

    private static void assertDoc(String expectedJson, Object actual) {
        if (expectedJson == null) {
            assertNull("expected no document, got " + Json.stringify(actual), actual);
            return;
        }
        Object expected = Json.parse(expectedJson);
        assertTrue("expected " + Json.stringify(expected) + " but got " + Json.stringify(actual),
                Json.deepEqual(expected, actual));
    }

    // ------------------------------------------------------ checkValidOp

    @Test
    public void allowsSomeSimpleValidOps() {
        json1.checkValidOp(null);
        valid("[{i:[1,2,3]}]");
        valid("[{r:{}}]");
        valid("[['x',{p:0}],['y',{d:0}]]");
        valid("[[0,{p:0}],[10,{d:0}]]");
        valid("[['a',{p:0}],['b',{d:0}],['x',{p:1}],['y',{d:1}]]");
        valid("[{e:'hi',et:'simple'}]");
        valid("[{es:['hi']}]");
        valid("[{ena:5}]");
    }

    @Test
    public void disallowsInvalidSyntax() {
        invalid("'hi'");    // not a list
        invalid("[{invalid:true}]");
        invalid("[10,{}]");
        invalid("[10,{invalid:true}]");
        invalid("[10,'hi']");
        invalid("[[{i:123}]]"); // descent with no key
    }

    @Test
    public void throwsIfThereIsAnyEmptyLeaves() {
        invalid("[]");
        invalid("['x']");
        invalid("['x',{}]");
        invalid("['x',[]]");
        invalid("[10]");
        invalid("[10,{}]");
        invalid("[10,[]]");
    }

    @Test
    public void ensuresPathComponentsAreNonZeroIntegersOrStrings() {
        invalid("[-1,{r:{}}]");
        invalid("[0.5,{r:{}}]");
        invalid("[true,{r:{}}]");
        invalid("[false,{r:{}}]");
        invalid("[null,{r:{}}]");
    }

    @Test
    public void disallowsProto() {
        invalid("['__proto__',{r:{}}]");
    }

    @Test
    public void doesNotAllowTwoPickupsOrTwoDropsInAComponent() {
        invalid("[{p:0,r:{}}]");
        invalid("[{p:1,r:{}}]");
        invalid("['x',{p:0,r:{}}]");
        invalid("['x',{p:1,r:{}}]");
        invalid("[{d:0,i:'hi'}]");
        invalid("[{d:1,i:'hi'}]");
        invalid("[10,{d:0,i:'hi'}]");
        invalid("[10,{d:1,i:'hi'}]");
    }

    @Test
    public void throwsIfThereAreMismatchedPickupsDrops() {
        invalid("[{p:0}]");
        invalid("[{d:0}]");
        invalid("['x',{p:0}]");
        invalid("[10,{p:0}]");
        invalid("['x',{d:0}]");
        invalid("[10,{d:0}]");
    }

    @Test
    public void throwsIfPickDropIndexesDontStartAt0() {
        invalid("[['x',{p:1}],['y',{d:1}]]");
        invalid("[[10,{p:1}],[20,{d:1}]]");
    }

    @Test
    public void throwsIfADescentStartsWithAnEdit() {
        invalid("[10,[{i:'hi'}]]");
    }

    @Test
    public void throwsIfDescentsAreOutOfOrder() {
        invalid("['x',['b',{r:{}}],['a',{r:{}}]]");
        invalid("['x',[10,{r:{}}],[5,{r:{}}]]");
        invalid("['x',['a',{r:{}}],[5,{r:{}}]]");
        invalid("['x',['a',{r:{}}],['a',{r:{}}]]");
        invalid("['x',[10,{r:{}}],[10,{r:{}}]]");
    }

    @Test
    public void throwsIfDescentsStartWithTheSameScalar() {
        invalid("['x',['a',{r:{}}],['a',{es:['hi']}]]");
    }

    @Test
    public void throwsIfDescentsHaveTwoAdjacentEdits() {
        invalid("[{r:{}},{p:0}]");
        invalid("['x',{r:{}},{p:0}]");
        invalid("['x',{r:{}},{p:0},'y',{r:{}}]");
    }

    @Test
    public void allowsImmediateDataDirectlyParentedInOtherImmediateData() {
        valid("[{i:{}},'x',{i:5}]");
        valid("[{i:{x:{}}},'x','y',{i:6}]");
        valid("[{i:[]},0,{i:5}]");
    }

    @Test
    public void doesNotAllowTheFinalItemToBeASingleDescent() {
        invalid("['a',['b',{r:{}}]]");
    }

    @Test
    public void doesNotAllowAnythingAfterTheDescentsAtTheEnd() {
        invalid("[[1,{r:{}}],[2,{r:{}}],5]");
        invalid("[[1,{r:{}}],[2,{r:{}}],5,{r:{}}]");
        invalid("[[1,{r:{}}],[2,{r:{}}],{r:{}}]");
    }

    @Test
    public void allowsRemovesInsideRemoves() {
        valid("['x',{r:true},'y',{r:true}]");
        valid("['x',{r:{}},'y',{r:true}]");
        valid("[['x',{r:true},'y',{p:0},'z',{r:true}],['y',{d:0}]]");
        valid("[['x',{r:{}},'y',{p:0},'z',{r:true}],['y',{d:0}]]");
    }

    @Test
    public void allowsInsertsInsideInserts() {
        valid("[1,{i:{}},'x',{i:10}]");
        valid("[[0,'x',{p:0}],[1,{i:{}},'x',{d:0},'y',{i:10}]]");
    }

    @Test
    public void editRequiresAllEditsToSpecifyTheirType() {
        invalid("[{e:{}}]");
        invalid("[5,{e:{}}]");
        valid("[{e:{},et:'simple'}]");
    }

    @Test
    public void allowsEditsToHaveNullOrFalseForTheOperation() {
        valid("[{e:null,et:'simple'}]");
        valid("[5,{e:null,et:'simple'}]");
        valid("[{e:false,et:'simple'}]");
        valid("[5,{e:false,et:'simple'}]");
    }

    @Test
    public void doesNotAllowAnEditToUseAnUnregisteredType() {
        invalid("[{e:{},et:'an undefined type'}]");
        invalid("[{e:null,et:'an undefined type'}]");
    }

    @Test
    public void doesNotAllowTwoEditsInTheSameComponent() {
        invalid("[{e:{},et:'simple',es:[1,2,3]}]");
        invalid("[{es:['hi'],ena:5}]");
        invalid("[{e:{},et:'simple',ena:5}]");
    }

    @Test
    public void failsIfTheTypeIsMissing() {
        invalid("[{et:'missing',e:{}}]");
    }

    // ------------------------------------------------------------- isNoop

    @Test
    public void isNoopWorks() {
        assertTrue(json1.isNoop(null));
        assertFalse(json1.isNoop(op("[{r:true}]")));
    }

    // ------------------------------------------------------------ normalize

    @Test
    public void normalizesSomeRegularOps() {
        n("[{i:'hi'}]");
        n("[{i:'hi'},1,2,3]", "[{i:'hi'}]");
        n("[[1,2,3,{p:0}],[1,2,3,{d:0}]]", "[1,2,3,{p:0,d:0}]");
        n("[[1,2,3,{p:0}],[1,2,30,{d:0}]]", "[1,2,[3,{p:0}],[30,{d:0}]]");
        n("[[1,2,30,{p:0}],[1,2,3,{d:0}]]", "[1,2,[3,{d:0}],[30,{p:0}]]");
    }

    @Test
    public void willLetYouInsertNull() {
        n("[{i:null}]");
    }

    @Test
    public void normalizesEmbeddedOpsWhenAvailable() {
        n("[{es:[0,'hi']}]", "[{es:['hi']}]");
        n("[{et:'text-unicode',e:['hi']}]", "[{es:['hi']}]");
        n("[{et:'text-unicode',e:[0,'hi']}]", "[{es:['hi']}]");
        n("[{et:'simple',e:{}}]");
        n("[{et:'number',e:5}]", "[{ena:5}]");
        n("[{ena:5}]");
    }

    @Test
    public void removesEmbeddedNoops() {
        n("[{es:[]}]", null);
        n("[['x',{es:[]}],['y',{r:true}]]", "['y',{r:true}]");
    }

    @Test
    public void correctsWeirdPickAndDropIds() {
        n("[['x',{p:1}],['y',{d:1}]]", "[['x',{p:0}],['y',{d:0}]]");
    }

    // ---------------------------------------------------------------- apply

    @Test
    public void canSetProperties() {
        apply("[]", "[0,{i:17}]", "[17]");
        apply("{}", "['x',{i:5}]", "{'x':5}");
    }

    @Test
    public void canEditTheRoot() {
        apply("{'x':5}", "[{r:true}]", null);
        apply("''", "[{r:true}]", null);
        apply("'hi'", "[{r:true,i:null}]", "null");
        apply("'hi'", "[{es:[2,' there']}]", "'hi there'");
        // Inserting over the top of an existing document needs a remove first.
        try {
            json1.apply(null, op("[{i:5}]"));
            apply("null", "[{i:5}]", "5");
        } catch (RuntimeException e) {
            fail("inserting into a missing document should work: " + e);
        }
        apply("{'x':5}", "[{r:{},i:[1,2,3]}]", "[1,2,3]");
    }

    @Test
    public void canMove1() {
        apply("{'x':5}", "[['x',{p:0}],['y',{d:0}]]", "{'y':5}");
    }

    @Test
    public void canMove2() {
        apply("[0,1,2]", "[[1,{p:0}],[2,{d:0}]]", "[0,2,1]");
    }

    @Test
    public void canHandleComplexListIndexStuff() {
        apply("[0,1,2,3,4,5]", "[[1,{r:{},i:11}],[2,{r:{},i:12}]]", "[0,11,12,3,4,5]");
    }

    @Test
    public void correctlyHandlesInterspersedDescentAndEdits() {
        apply("{'x':{'y':{'was':'y'},'was':'x'}}",
                "[['X',{d:0},'Y',{d:1}],['x',{p:0},'y',{p:1}]]",
                "{'X':{'Y':{'was':'y'},'was':'x'}}");
    }

    @Test
    public void canEditStrings() {
        apply("'errd'", "[{es:[2,'maghe']}]", "'ermagherd'");
    }

    @Test
    public void canEditNumbers() {
        apply("5", "[{ena:10}]", "15");
    }

    @Test
    public void canEditChildNumbers() {
        apply("[20]", "[0,{ena:-100}]", "[-80]");
    }

    @Test
    public void canEditSubdocumentsUsingAnEmbeddedType() {
        apply("{'str':'hai'}", "[{e:{position:2,text:'wai'},et:'simple'}]", "{'str':'hawaii'}");
    }

    @Test
    public void appliesEditsAfterDrops() {
        apply("{'x':'yooo'}", "[['x',{p:0}],['y',{d:0,es:['sup']}]]", "{'y':'supyooo'}");
    }

    @Test
    public void throwsWhenTheOpTraversesMissingItems() {
        expectThrows(() -> json1.apply(doc("[0,'hi']"), op("[1,{p:0},'x',{d:0}]")));
        expectThrows(() -> json1.apply(doc("{}"), op("[{p:0},'a',{d:0}]")));
    }

    @Test
    public void throwsWhenTheOpInsertsInOutOfBoundIndex() {
        expectThrows(() -> json1.apply(doc("[0]"), op("[2,{i:2}]")));
    }

    // ------------------------------------------------- transformPosition

    @Test
    public void doesNotModifyPathWhenOpIsUnrelated() {
        path("['a','b','c']", "null");
        path("['a','b','c']", "['x',{i:5}]");
        path("['a','b','c']", "['x',{r:true}]");
        path("['a','b','c']", "[['x',{p:0}],['y',{d:0}]]");
        path("[1,2,3]", "[2,{i:5}]");
        path("[1,2,3]", "[1,2,4,{i:5}]");
        path("[1]", "[1,2,{r:true}]");
        path("['x']", "['x','y',{r:true}]");
    }

    @Test
    public void adjustsListIndicies() {
        path("[2]", "[1,{i:5}]", "[3]");
        path("[2]", "[2,{i:5}]", "[3]");
        path("[2]", "[1,{r:true}]", "[1]");
        path("[2]", "[[1,{p:0}],[3,{d:0}]]", "[1]");
        path("[2]", "[[1,{d:0}],[3,{p:0}]]", "[3]");
        path("[2]", "[[2,{d:0}],[3,{p:0}]]", "[3]");
    }

    @Test
    public void returnsNullWhenTheObjectAtThePathWasRemoved() {
        path("['x']", "[{r:true}]", null);
        path("['x']", "['x',{r:true}]", null);
        path("[1]", "[{r:true}]", null);
        path("[1]", "[1,{r:true}]", null);
    }

    @Test
    public void movesThePath() {
        path("['a','z']", "[['a',{p:0}],['y',{d:0}]]", "['y','z']");
        path("['a','b']", "[['a','b',{p:0}],['z',{d:0}]]", "['z']");
        path("['a','b']", "[['a','b','c',{p:0}],['z',{d:0}]]");
        path("[1,2]", "[[1,{p:0}],[10,{d:0}]]", "[10,2]");
        path("[1,2]", "[[1,2,{p:0}],[10,{d:0}]]", "[10]");
        path("[1,2]", "[1,[1,{d:0}],[2,{p:0}]]", "[1,1]");
        path("[1,2]", "[[1,2,3,{p:0}],[10,{d:0}]]");
    }

    @Test
    public void handlesPickParentAndMove() {
        path("['a','b','c']", "[['a',{r:true},'b',{p:0}],['x',{d:0}]]", "['x','c']");
    }

    @Test
    public void adjustsIndiciesUnderAPick() {
        path("['a','b',10]", "[['a',{p:0},'b',1,{r:true}],['x',{d:0}]]", "['x','b',9]");
    }

    @Test
    public void callsTransformPositionWithEmbeddedStringEditsIfAvailable() {
        path("['x','y','z',1]", "['x','y','z',{es:['abc']}]", "['x','y','z',4]");
        path("['x','y','z',1]", "['x','y','z',{es:['\uD83D\uDC83']}]", "['x','y','z',2]");
        path("['x','y','z']", "['x','y','z',{es:['\uD83D\uDC83']}]", "['x','y','z']");
    }

    @Test
    public void transformPositionWorksCorrectlyWithPicksAndDrops() {
        path("['na',0,'nb',0]",
                "[{r:true,d:0},['na',0,{p:0}],['nb',0,{i:{object:'four'}}]]", "['nb',1]");
    }

    // -------------------------------------------------- makeInvertible

    @Test
    public void makeInvertibleIsSane() {
        assertNull(json1.makeInvertible(null, null));
        mi("[{i:'hi'}]");
        mi("[2,{en:50}]");
        mi("[{r:true}]", "'hi'", "[{r:'hi'}]");
        mi("[2,{r:true}]", "[0,1,'hi']", "[2,{r:'hi'}]");
    }

    @Test
    public void ignoresDescentsIntoOtherPartsOfTheOp() {
        mi("[['x',{r:true}],['y',{i:5}]]", "{'x':'hi'}", "[['x',{r:'hi'}],['y',{i:5}]]");
    }

    @Test
    public void recursivelyCallsMakeInvertibleOnEmbeddedOperations() {
        mi("[{es:[1,{d:5}]}]", "'abcdef'", "[{es:[1,{d:'bcdef'}]}]");
    }

    @Test
    public void onlyEmbedsRemoveDataNotCoveredByChildRemovesObj() {
        mi("[{r:true},'y',{r:true}]", "{'x':5,'y':6}", "[{r:{'x':5}},'y',{r:6}]");
    }

    @Test
    public void onlyEmbedsRemoveDataNotCoveredByChildRemovesList() {
        mi("[{r:true},1,{r:true}]", "['a','b','c']", "[{r:['a','c']},1,{r:'b'}]");
    }

    @Test
    public void adjustsListOffsetsCorrectly() {
        mi("[{r:true},[0,{r:true}],[2,{r:true}]]", "['a','b','c']",
                "[{r:['b']},[0,{r:'a'}],[2,{r:'c'}]]");
    }

    @Test
    public void callsMakeInvertibleOnMovedChildren() {
        mi("[['x',{p:0}],['y',{d:0,es:[{d:5}]}]]", "{'x':'abcde'}",
                "[['x',{p:0}],['y',{d:0,es:[{d:'abcde'}]}]]");
    }

    @Test
    public void callsMakeInvertibleOnInsertedChildren() {
        mi("['x',{i:'hi',es:[{d:2}]}]", "{}", "['x',{i:'hi',es:[{d:'hi'}]}]");
    }

    @Test
    public void adjustsPositionForMakeInvertibleBasedOnRemoves() {
        mi("[[0,{r:true}],[1,{es:[{d:2}]}]]", "['a','b','hi']",
                "[[0,{r:'a'}],[1,{es:[{d:'hi'}]}]]");
    }

    @Test
    public void adjustsPositionForMakeInvertibleBasedOnRemoves2() {
        mi("[0,{r:true,es:[{d:2}]}]", "['a','hi']", "[0,{r:'a',es:[{d:'hi'}]}]");
    }

    @Test
    public void adjustsPositionForMakeInvertibleBasedOnInserts() {
        mi("[[0,{i:'a'}],[2,{es:[{d:2}]}]]", "['b','hi']",
                "[[0,{i:'a'}],[2,{es:[{d:'hi'}]}]]");
    }

    @Test
    public void makeInvertibleRepro1() {
        mi("[['b',{i:null}],['y',{r:true}]]", "{'y':'omg'}",
                "[['b',{i:null}],['y',{r:'omg'}]]");
    }

    @Test
    public void makeInvertibleRepro2() {
        mi("[['b',{d:0}],['z',{r:true},1,{p:0}]]", "{'z':[1,'whoa',3]}",
                "[['b',{d:0}],['z',{r:[1,3]},1,{p:0}]]");
    }

    @Test
    public void ignoresRmBasedIndexOffsetsWhenIteratingThroughAnInsertLiteral() {
        mi("['z',{r:true,i:['mimsy']},0,{r:true,es:[{d:3}]}]", "{'z':['x']}",
                "['z',{r:[],i:['mimsy']},0,{r:'x',es:[{d:'mim'}]}]");
    }

    @Test
    public void takesIntoAccountInsertIndexOffsetsThroughAnInsertLiteral() {
        mi("[{i:['abc'],r:true},[0,{i:'a'}],[1,{es:[{d:3}]}]]", "true",
                "[{i:['abc'],r:true},[0,{i:'a'}],[1,{es:[{d:'abc'}]}]]");
    }

    // ------------------------------------------------------------- invert

    @Test
    public void invertsChildOperations() {
        i("[{es:['x']}]", "[{es:[{d:'x'}]}]", "'_'");
    }

    @Test
    public void invertsChildOperations2() {
        i("[2,{es:['x']}]", "[2,{es:[{d:'x'}]}]", "[0,1,'_']");
    }

    @Test
    public void swapsPickAndDropLocations() {
        i("[['x',{p:0}],['y',{d:0}]]", "[['x',{d:0}],['y',{p:0}]]", "{'x':5}");
    }

    @Test
    public void swapsPickAndDropLocationsInAWayThatNormalizesCanonically() {
        i("[['a',{p:0}],['b',{p:1}],['c',{d:0}],['d',{d:1}]]",
                "[['a',{d:0}],['b',{d:1}],['c',{p:0}],['d',{p:1}]]", "{'a':5,'b':6}");
    }

    @Test
    public void teleportsEmbeddedEditsBasedOnTheParentMove() {
        i("[['x',{p:0}],['y',{d:0},0,{es:['hi']}]]",
                "[['x',{d:0},0,{es:[{d:'hi'}]}],['y',{p:0}]]", "{'x':['_']}");
    }

    @Test
    public void transformsIndexesBasedOnPickLocations() {
        i("[[0,{r:true}],[1,{es:['hi']}]]", "[[0,{i:true}],[2,{es:[{d:'hi'}]}]]",
                "[true,123,'_']");
    }

    @Test
    public void transformsIndexesBasedOnDropLocations() {
        i("[[0,{i:true}],[1,{es:['hi']}]]", "[0,{r:true,es:[{d:'hi'}]}]", "['_']");
    }

    @Test
    public void anOperationThatsImmediatelyEditedIsRemovedEntirely() {
        i("[{i:'hi',es:['x']}]", "[{r:'xhi'}]");
    }

    @Test
    public void anOperationThatsImmediatelyEditedInternallyIsRemovedEntirely() {
        i("[{i:{'x':'hi'}},'x',{es:['x']}]", "[{r:{'x':'xhi'}}]");
    }

    @Test
    public void ignoresEditsThatHaveBeenBaked() {
        i("[['a',{i:'hi',es:['x']}],['b',{es:['y']}]]",
                "[['a',{r:'xhi'}],['b',{es:[{d:'y'}]}]]", "{'b':'_'}");
    }

    @Test
    public void insInsEdit() {
        i("[{i:{}},'x',{i:'_',es:['x']}]", "[{r:{}},'x',{r:'x_'}]");
    }

    @Test
    public void insInsEdit2() {
        i("[{i:{}},'x',{i:['_']},0,{es:['x']}]", "[{r:{}},'x',{r:['x_']}]");
    }

    @Test
    public void insDropEdit() {
        i("[['a',{p:0}],['b',{i:{}},'a',{d:0,es:['x']}]]",
                "[['a',{d:0,es:[{d:'x'}]}],['b',{r:{}},'a',{p:0}]]", "{'a':'_'}");
    }

    @Test
    public void insDropEdit2() {
        i("[['a',{p:0}],['b',{i:{}},'a',{d:0},'x',{es:['x']}]]",
                "[['a',{d:0},'x',{es:[{d:'x'}]}],['b',{r:{}},'a',{p:0}]]", "{'a':{'x':'_'}}");
    }

    @Test
    public void adjustsIndexesWhenItBakesBasedOffInlineInserts() {
        i("[{i:[99]},[0,{i:null}],[1,{ena:1}]]", "[{r:[100]},0,{r:null}]");
    }

    @Test
    public void traversalPicksTheRightIndexInComplexSituations() {
        i("[['a',{i:{}},'b',{d:0}],['z',[0,{i:'',r:1}],[1,{p:0,ena:9}]]]",
                "[['a',{r:{}},'b',{p:0}],['z',[0,{i:1,r:''}],[1,{d:0}],[2,{ena:-9}]]]",
                "{'x':'hi','y':'omg','z':[1,'whoa',3]}");
    }

    // ------------------------------------------------------------ compose

    @Test
    public void composesEmptyOpsToNothing() {
        assertNull(Json1.INSTANCE.compose(null, null));
    }

    @Test
    public void composeOp1Drop() {
        compose("[['x',{p:0}],['y',{d:0}]]", "['y',{r:true}]", "['x',{r:true}]");
        compose("[['x',{p:0}],['y',0,{d:0}]]", "['y',{r:true}]",
                "[['x',{r:true}],['y',{r:true}]]");
        compose("[['x',{p:0}],['y',{d:0}]]", "['y','a',{r:true}]",
                "[['x',{p:0},'a',{r:true}],['y',{d:0}]]");
        compose("[['x',{p:0}],['y',{d:0}]]", "[['y',{r:true},'a',{p:0}],['z',{d:0}]]",
                "[['x',{r:true},'a',{p:0}],['z',{d:0}]]");
        compose("[['x',{p:0}],['y',{d:0}]]", "[['y',{p:0}],['z',{d:0}]]",
                "[['x',{p:0}],['z',{d:0}]]");
        compose("[['x',{p:0}],['y',10,{d:0}]]", "['y',0,{r:true}]",
                "[['x',{p:0}],['y',[0,{r:true}],[9,{d:0}]]]");
    }

    @Test
    public void composeOp1Insert() {
        compose("['x',{i:{'a':'hi'}}]", "['x',{r:true}]", null);
        compose("['x',0,{i:{'a':'hi'}}]", "['x',{r:true}]", "['x',{r:true}]");
        compose("['x',{i:{'a':'hi','b':'woo'}}]", "['x','a',{r:true}]", "['x',{i:{'b':'woo'}}]");
        compose("['x',{i:{'a':'hi','b':'woo'}}]", "[['x',{r:true},'a',{p:0}],['y',{d:0}]]",
                "['y',{i:'hi'}]");
        compose("['x',{i:{}},'y',{i:'hi'}]", "['x','y',{r:true}]", "['x',{i:{}}]");
        compose("['x',{i:{}},'y',{i:[1,2,3]}]", "['x','y',1,{r:true}]",
                "['x',{i:{}},'y',{i:[1,3]}]");
        compose("['x',{i:['a','b','c']},1,{i:'XX'}]", "[['x',1,{p:0}],['y',{d:0}]]",
                "[['x',{i:['a','b','c']}],['y',{i:'XX'}]]");
        compose("['x',{i:['a','b','c']},1,{i:'XX'}]", "[['x',3,{p:0}],['y',{d:0}]]",
                "[['x',{i:['a','b']},1,{i:'XX'}],['y',{i:'c'}]]");
        compose("['x',{i:{}},'y',{i:[1,2,3]}]", "[['x',{p:0}],['z',{d:0}]]",
                "['z',{i:{}},'y',{i:[1,2,3]}]");
        compose("['x',{i:{}},'y',{i:[1,2,3]}]", "['x',{r:true}]", null);
        compose("[['x',{p:0}],['y',{d:0},0,{i:'hi'}]]", "['y',{r:true}]", "['x',{r:true}]");
        compose("[{i:{}}]", "['x',{i:'hi'}]", "[{i:{}},'x',{i:'hi'}]");
        compose("[{i:'hi'}]", "[{es:[2,' there']}]", "[{i:'hi',es:[2,' there']}]");
        compose("[{i:10}]", "[{ena:20}]", "[{i:10,ena:20}]");
    }

    @Test
    public void composeOp1Edit() {
        compose("['x',{es:['hi']}]", "['x',{r:true}]", "['x',{r:true}]");
        compose("['x',{i:'',es:['hi']}]", "['x',{r:true}]", null);
        compose("['x',{i:['']},0,{es:['hi']}]", "['x',0,{r:true}]", "['x',{i:[]}]");
        compose("[{es:['hi']}]", "[{es:[2,' there']}]", "[{es:['hi there']}]");
        compose("[{ena:10}]", "[{ena:-8}]", "[{ena:2}]");
        compose("[{es:['hi']}]", "[{es:[{d:'hi'}]}]", null);
        compose("['x',{es:['hi']}]", "[['x',{p:0}],['y',{d:0,es:[2,' there']}]]",
                "[['x',{p:0}],['y',{d:0,es:['hi there']}]]");
        compose("['x',{i:'hi'}]", "[['x',{p:0}],['y',{d:0,es:[' there']}]]",
                "['y',{i:'hi',es:[' there']}]");
        compose("['x',{es:['hi']}]", "['x',{r:true,i:'yo',es:[2,' there']}]",
                "['x',{r:true,i:'yo',es:[2,' there']}]");
    }

    @Test
    public void composesSimpleRegressions() {
        compose("[0,{p:0,d:0}]", "[{r:true}]", "[{r:true},0,{r:true}]");
        compose("['a',1,{r:true}]", "['a',{r:true}]", "['a',{r:true},1,{r:true}]");
    }

    @Test
    public void composeSkipsOp2DropsWhenCalculatingOp1DropIndex() {
        compose("[[0,{p:0}],[2,{d:0}]]", "[[0,{p:0}],[1,{d:0}]]",
                "[[0,{p:1}],[1,{p:0,d:0}],[2,{d:1}]]");
        compose("[[0,{p:0,d:1}],[1,{p:1}],[2,{d:0}]]", "[[0,{p:0}],[1,{d:0}]]",
                "[[0,{p:1}],[1,{p:0,d:0}],[2,{d:1}]]");
    }

    // ----------------------------------------------------------- transform

    /**
     * Like the reference suite's xf(): transform op1 against op2 on both sides.
     */
    private static void xf(String op1Json, String op2Json, String expectJson) {
        xf(op1Json, op2Json, expectJson, expectJson);
    }

    private static void xf(String op1Json, String op2Json, String expectLeft, String expectRight) {
        List<Object> op1 = op1Json == null ? null : op(op1Json);
        List<Object> op2 = op2Json == null ? null : op(op2Json);

        Json1.TransformResult left = json1.tryTransform(op1, op2, Side.LEFT);
        Json1.TransformResult right = json1.tryTransform(op1, op2, Side.RIGHT);
        assertTrue("left transform conflicted: " + (left.ok ? "" : left.conflict), left.ok);
        assertTrue("right transform conflicted: " + (right.ok ? "" : right.conflict), right.ok);
        assertOp("xf left", expectLeft == null ? null : op(expectLeft), left.result);
        assertOp("xf right", expectRight == null ? null : op(expectRight), right.result);
    }

    /**
     * Transform with conflicts resolved on the spot, as the reference suite does for conflict cases.
     */
    private static void xfc(String op1Json, String op2Json, String expectJson) {
        List<Object> result = json1.transformNoConflict(op(op1Json), op(op2Json), Side.LEFT);
        assertOp("transformNoConflict", expectJson == null ? null : op(expectJson), result);
    }

    @Test
    public void transformFoo() {
        xf("[['x',['a',{p:0}],['b',{d:0}]],['y',['a',{p:1}],['b',{d:1}]]]",
                "['x',{r:true}]", "['y',['a',{p:0}],['b',{d:0}]]");
    }

    @Test
    public void canReparentWithSomeExtraJunk() {
        xf("[['x',{p:0}],['y',{d:0}]]",
                "[['_a',{d:1}],['_x',{d:0}],['x',{p:0},'a',{p:1}]]",
                "[['_x',{p:0}],['y',{d:0}]]", null);
    }

    @Test
    public void awfulDeleteNonsense() {
        xf("[['x',{r:true}],['y',{i:'hi'}]]", "[['x','a',{p:0}],['y',{d:0}]]",
                "[['x',{r:true}],['y',{r:true,i:'hi'}]]");
        xf("[['x','a',{p:0}],['y',{d:0}]]", "[['x',{r:true}],['y',{i:'hi'}]]", null);
        xf("[10,{r:true}]", "[[5,{d:0}],[10,1,{p:0}]]", "[[5,{r:true}],[11,{r:true}]]");
    }

    @Test
    public void swap() {
        String swap = "[['a',{p:0},'b',{p:1}],['b',{d:1},'a',{d:0}]]";
        xf(null, swap, null);
        xf("['a',{es:['a edit']},'b',{es:['b edit']}]", swap,
                "['b',{es:['b edit']},'a',{es:['a edit']}]");
    }

    @Test
    public void canRewriteSimpleListIndexes() {
        xf("[10,{es:['edit']}]", "[0,{i:'oh hi'}]", "[11,{es:['edit']}]");
        xf("[10,{r:true}]", "[0,{i:'oh hi'}]", "[11,{r:true}]");
        xf("[10,{i:{}}]", "[0,{i:'oh hi'}]", "[11,{i:{}}]");
    }

    @Test
    public void canChangeTheRootFromAnObjectToList() {
        xf("['a',{es:['hi']}]", "[{i:[],r:true},[0,{d:0}],['a',{p:0}]]", "[0,{es:['hi']}]");
    }

    @Test
    public void canHandleAdjacentDrops() {
        xf("[[11,{i:1}],[12,{i:2}],[13,{i:3}]]", "[0,{r:true}]",
                "[[10,{i:1}],[11,{i:2}],[12,{i:3}]]");
    }

    @Test
    public void fixesDropIndexesCorrectly() {
        xf("[[0,{r:true}],[1,{i:'hi'}]]", "[1,{r:true}]", "[0,{r:true,i:'hi'}]");
        xf("[[0,{r:true}],[1,{i:'hi'}]]", "[2,{r:true}]", "[[0,{r:true}],[1,{i:'hi'}]]");
    }

    @Test
    public void listDropVsDeleteUsesTheCorrectResultIndex() {
        xf("[2,{i:'hi'}]", "[2,{r:true}]", "[2,{i:'hi'}]");
        xf("[3,{i:'hi'}]", "[2,{r:true}]", "[2,{i:'hi'}]");
    }

    @Test
    public void listDropVsDropUsesTheCorrectResultIndex() {
        xf("[2,{i:'hi'}]", "[2,{i:'other'}]", "[2,{i:'hi'}]", "[3,{i:'hi'}]");
    }

    @Test
    public void listDropVsDeleteAndDrop() {
        xf("[2,{i:'hi'}]", "[2,{r:true,i:'other'}]", "[2,{i:'hi'}]", "[3,{i:'hi'}]");
        xf("[3,{i:'hi'}]", "[[2,{r:true}],[3,{i:'other'}]]", "[2,{i:'hi'}]");
        xf("[4,{i:'hi'}]", "[[2,{r:true}],[3,{i:'other'}]]", "[3,{i:'hi'}]", "[4,{i:'hi'}]");
    }

    @Test
    public void listDeleteVsDrop() {
        xf("[1,{r:true}]", "[2,{i:'hi'}]", "[1,{r:true}]");
        xf("[2,{r:true}]", "[2,{i:'hi'}]", "[3,{r:true}]");
        xf("[3,{r:true}]", "[2,{i:'hi'}]", "[4,{r:true}]");
    }

    @Test
    public void listDeleteVsDelete() {
        xf("[1,{r:true}]", "[1,{r:true}]", null);
    }

    @Test
    public void insertVsDeleteParent() {
        // Inserting into something the other operation removed is a conflict - the reference
        // resolves it by dropping the insert.
        xfc("[2,'x',{i:'hi'}]", "[2,{r:true}]", null);
    }

    @Test
    public void transformsAgainstInsertsInMyOwnList() {
        xf("[[0,{i:'a'}],[2,{i:'b'}]]", "[1,{r:true}]", "[[0,{i:'a'}],[2,{i:'b'}]]");
    }

    @Test
    public void vsCancelledOp2Drop() {
        xf("[['x',{r:true}],['y',3,{i:5}]]", "[['x','a',{p:0}],['y',2,{d:0}]]",
                "[['x',{r:true}],['y',[2,{r:true}],[3,{i:5}]]]");
    }

    @Test
    public void vsCancelledOp1Drop() {
        xf("[['x',{p:0}],['y',[3,{d:0}],[4,{i:5}]]]", "['x',{r:true}]", "['y',3,{i:5}]");
    }

    @Test
    public void vsCancelledOp1Pick() {
        xf("[[1,{p:0}],[4,{r:true,i:4}],[6,{d:0}]]", "[1,{r:true}]", "[[3,{r:true}],[4,{i:4}]]");
    }

    @Test
    public void transformsEditsByOneAnother() {
        xf("[1,{es:[2,'hi']}]", "[1,{es:['yo']}]", "[1,{es:[4,'hi']}]");
    }

    @Test
    public void editsAtTheRootAreTransformedByTheSubtype() {
        xf("[{e:{position:2,text:'wai'},et:'simple'}]",
                "[{e:{position:0,text:'omg'},et:'simple'}]",
                "[{e:{position:5,text:'wai'},et:'simple'}]");
    }

    @Test
    public void appliesEditsInTheRightOrder() {
        xf("[1,{es:[2,'hi']}]", "[[1,{i:{}}],[2,{es:['yo']}]]", "[2,{es:[4,'hi']}]");
    }

    @Test
    public void copiesInOpsOtherwise() {
        xf("['x',{e:{position:2,text:'wai'},et:'simple'}]", "['y',{r:true}]",
                "['x',{e:{position:2,text:'wai'},et:'simple'}]");
    }

    @Test
    public void countsPositionsInCodePoints() {
        // '💃' is a single position as far as the text subtype is concerned.
        apply("'\uD83D\uDC83hi'", "[{es:[1,'X']}]", "'\uD83D\uDC83Xhi'");
        mi("[{es:[{d:2}]}]", "'a\uD83D\uDC83b'", "[{es:[{d:'a\uD83D\uDC83'}]}]");
        apply("'a\uD83D\uDC83b'", "[{es:[{d:'a\uD83D\uDC83'}]}]", "'b'");
    }

    // ----------------------------------------------------------- plumbing

    private static void expectThrows(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | AssertionError e) {
            return;
        }
        fail("expected an exception");
    }

    /**
     * The 'simple' subtype the reference tests register: it wraps a document of the form
     * {@code {str: '...'}} and edits it by inserting text at a position.
     */
    private static final class SimpleSubtype extends Subtype {
        SimpleSubtype() {
            name = "simple";
            uri = "simple";
        }

        @SuppressWarnings("unchecked")
        @Override
        public Object apply(Object doc, Object op) {
            Map<String, Object> d = (Map<String, Object>) doc;
            Map<String, Object> o = (Map<String, Object>) op;
            if (o == null) return doc;
            String str = (String) d.get("str");
            int pos = ((Number) o.get("position")).intValue();
            String text = (String) o.get("text");
            Map<String, Object> result = new java.util.LinkedHashMap<>(d);
            result.put("str", str.substring(0, pos) + text + str.substring(pos));
            return result;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Object compose(Object op1, Object op2) {
            Map<String, Object> a = (Map<String, Object>) op1;
            Map<String, Object> b = (Map<String, Object>) op2;
            if (a == null) return b;
            if (b == null) return a;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("position", ((Number) a.get("position")).intValue()
                    + ((Number) b.get("position")).intValue());
            out.put("text", (String) b.get("text") + (String) a.get("text"));
            return out;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Object transform(Object op1, Object op2, Side side) {
            Map<String, Object> a = (Map<String, Object>) op1;
            Map<String, Object> b = (Map<String, Object>) op2;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            int shift = ((String) b.get("text")).length();
            int pa = ((Number) a.get("position")).intValue();
            int pb = ((Number) b.get("position")).intValue();
            // Only text inserted ahead of ours pushes us along; ties are broken by the side.
            boolean moved = pa > pb || (pa == pb && side == Side.LEFT);
            out.put("position", pa + (moved ? shift : 0));
            out.put("text", a.get("text"));
            return out;
        }
    }
}
