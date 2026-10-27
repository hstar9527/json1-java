# json1-java

`ot-json1`（JSON 协同 OT 类型）的 Java 移植版，对应 TypeScript 实现
[ottypes/json1](https://github.com/ottypes/json1)（`lib/json1.ts`、`lib/cursor.ts`）。

它同时是 [ShareDB](https://github.com/share/sharedb) 里 JSON0 的后继者：操作（operation）
镜像文档的形状，按 **拾取 → 丢弃 → 编辑** 三个阶段描述一次改动。

Maven 结构，Java 8，只依赖 JUnit 4（仅测试用）。

```
json1-java/
  pom.xml
  src/main/java/com/ottypes/json1/
    Json.java              JSON 值模型：undefined 哨兵、深拷贝/深比较、键排序、容器读写、解析与序列化
    OpComponent.java       操作组件（保持引用语义，transform 需要按身份识别组件）
    Subtype.java           嵌入子类型接口（side 枚举、可选钩子）
    TextUnicodeSubtype.java text-unicode 子类型（内置，位置按 Unicode **码点** 计数）
    NumberSubtype.java      number 子类型（内置）
    ConflictType.java / Conflict.java / ConflictException.java
    Cursor.java / ReadCursor.java / WriteCursor.java   游标子系统
    Advancer.java          两个游标协同遍历的辅助工具
    Json1.java             核心：apply / compose / transform / invert / normalize ...
    Transformer.java       tryTransform 的实现
  src/test/java/com/ottypes/json1/Json1Test.java        核心用例 + 随机测试
  src/test/java/com/ottypes/json1/Json1SpecTest.java    test/test.js 逐条移植的用例
```

## 构建与测试

```bash
mvn test          # 或离线：mvn -o test
```

## 快速上手

```java
import com.ottypes.json1.*;
import com.ottypes.json1.Subtype.Side;

Json1 json1 = Json1.INSTANCE;

List<Object> op1 = json1.moveOp(Arrays.asList("a", "x"), Arrays.asList("a", "y"));
List<Object> op2 = json1.compose(
    json1.moveOp(Arrays.asList("a"), Arrays.asList("b")),
    json1.insertOp(Arrays.asList("b", "z"), "hi there"));

// 并发操作互相变换
List<Object> op1_ = json1.transform(op1, op2, Side.LEFT);   // op2 之后应用
List<Object> op2_ = json1.transform(op2, op1, Side.RIGHT);  // op1 之后应用

Object doc = Json.parse("{\"a\":{\"x\":5}}");
Object left  = json1.apply(json1.apply(doc, op1), op2_);
Object right = json1.apply(json1.apply(doc, op2), op1_);
// left、right 都等于 {"b":{"y":5,"z":"hi there"}}
```

## 文档与操作的表示

| JSON 值 | Java |
| --- | --- |
| 对象 | `Map<String, Object>`（`LinkedHashMap`，键顺序即排序） |
| 数组 | `List<Object>` |
| 字符串 / 数字 / 布尔 | `String` / `Integer` / `Double` / `Boolean` |
| null | `null` |
| “不存在”（undefined） | `Json.UNDEFINED` 哨兵（内部使用） |

操作同样是 `List<Object>`：路径项（`String` 或 `Integer`）、嵌套子列表（`List`）、
组件（`Map`，实际是 `OpComponent`）。组件里可以写：

| 字段 | 含义 |
| --- | --- |
| `p`: n | pick，把当前子树拾到第 n 号槽位 |
| `d`: n | drop，把第 n 号槽位的内容放到当前位置 |
| `r`: any | remove，丢弃当前子树（`any` 是内容，想支持逆向就带上） |
| `i`: value | insert，在当前位置插入字面值 |
| `es` / `ena` / `et`+`e` | 嵌入编辑：text-unicode / number / 其它子类型 |

## 主要 API（`Json1`）

| 方法 | 说明 |
| --- | --- |
| `apply(snapshot, op)` | 应用操作，返回新快照（原快照不会被修改） |
| `compose(op1, op2)` | 合并两个顺序操作 |
| `transform(op1, op2, side)` | 变换；冲突时抛 `ConflictException` |
| `tryTransform(op1, op2, side)` | 返回 `TransformResult`：`ok` 或 `conflict` |
| `transformNoConflict(op1, op2, side)` | 变换，冲突就地解决（删掉冲突内容） |
| `transformWithConflictsPred(pred, op1, op2, side)` | 逐个询问冲突可否接受，直到没有冲突 |
| `typeAllowingConflictsPred(pred)` | 返回一个 `Json1` 视图，其 `transform` 自动解决冲突 |
| `normalize(op)` | 规范化操作（排序、压缩、去掉空组件） |
| `checkValidOp(op)` | 校验，失败抛异常 |
| `invert(op)` / `invertWithDoc(op, doc)` / `makeInvertible(op, doc)` | 求逆 |
| `transformPosition(path, op)` | 变换文档中的路径；目标被删除时返回 null |
| `insertOp / removeOp / moveOp / replaceOp / editOp` | 便捷构造器 |
| `WriteCursor` | 手工构建操作时维护规范形式的游标 |

`side` 用 `Subtype.Side.LEFT` / `RIGHT`，也可以用字符串 `"left"` / `"right"`。
约定：`transform(op1, op2, LEFT)` 得到的操作在 **op2 之后** 应用。

## 子类型

注册：`Json1.registerSubtype(new MySubtype())`（按 `name` 或 `uri` 索引）。
内置 `text-unicode`（字符串编辑）与 `number`（数字累加）。子类型必须实现
`apply`；`compose / transform / invert / normalize / makeInvertible / transformPosition`
是可选钩子。

## 移植说明（TS → Java 的差异）

1. **类型**：文档和操作都是 `Object` / `List<Object>` / `Map<String,Object>`。
   对象键顺序代表排序，`Json.compareKeys` 负责“数字排在字符串前面”的规则。
2. **undefined**：JS 的 `undefined` 由 `Json.UNDEFINED` 哨兵表示（`Json.maybeGetChild` 等
   在键不存在时返回它），内部判断一律用它而不是 `null`。注意：传给 `apply` 的
   `null` 快照表示“没有文档”，如果你想表达 JSON 的 `null` 根节点，请用
   `Json.UNDEFINED` 之外的包装方式（本库中该场景未被使用）。
3. **组件身份**：`transform` 用集合按 *引用* 记录访问过的组件，因此组件用
   `OpComponent`（引用相等）表示。从 JSON 文本解析出来的操作请用 `Json1.asOp(...)`
   或者在调用入口处转换，`Json.parse` 得到的普通 map 会被自动视作组件内容。
4. **游标**：`ReadCursor` 支持 `for (Object key : cursor)` 遍历子节点（遍历前后游标
   位置自动恢复），`WriteCursor` 延迟处理下降路径并在写入时维护规范形式。
5. **API 风格**：返回 `TransformResult`（而不是 TS 的联合类型），冲突用
   `ConflictException` 抛出；`transform(op, other, "left")` 等价字符串形式也提供了。
6. **Unicode**：`text-unicode` 的一切位置/长度按 **码点** 计数（与参考实现一致），
   因此 emoji 等增补平面字符只占 1 个位置，而不是 Java `String.length()` 的 2。
   `apply`、`transformPosition`、`makeInvertible`、compose 的插入长度、transform 都按码点处理。

## 测试

`mvn -o test` 目前共 **157 个用例**。

`Json1SpecTest` 是原仓库 `test/test.js` 的逐条移植：校验（含 edited 组件的
`et` 规则）、isNoop、normalize、apply、transformPosition、makeInvertible、invert、
compose、transform。测试数据直接用参考套件里的宽松 JSON 写法（单引号、裸键），
`Json.parse` 可以解析。

`Json1Test` 覆盖同样的功能面（偏重端到端）并外加随机测试：

* `randomOpsConverge` — 验证变换性质 TP1：先 op1 再 op2' 与先 op2 再 op1' 结果一致；
* `randomConflictsResolve` — 冲突解决后双方文档仍然一致；
* `randomOpsInvert` / `randomOpsCompose` — 逆操作与合成本身的正确性。
