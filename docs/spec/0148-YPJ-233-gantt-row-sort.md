# spec 0148 — YPJ-233 甘特行排序:同级按 start 升序,未排期靠后

> 票:YPJ-233(4h,sprint V31)。语义用户 2026-11-18 已拍板,**勿改语义**。
> 分支:`feat/YPJ-233-gantt-row-sort`(yz 建,已关联)。

## 一、需求

### 背景
甘特行序 = 后端 `ProjectService.gantt()` 的 `walk()` 输出序;当前同级按 `number`(创建序)排。用户在看板粒度下观察:行序与时间无关,时间上相邻的 item 散落各处,关系不好读。

### 范围(拍板语义,2026-11-18)
1. **同级按 start 升序**:同一父下的兄弟 item,按 `startDate` 从早到晚排。
2. **未排期靠后**:无 `startDate` 的 item 落同级尾部。
3. **子挂父下,组内亦按 start**:排序在树的每一层递归生效(父下子组、孙组同规则)。
4. 并列与稳定性:同 start 按 `number`(创建序)兜底,结果全确定。

### 语义断言(⚠ 请人复核)
- 「未排期」按拍板字面 = **无 start**。故「只有 due 的里程碑型 item(菱形)」无 start → 落同级未排期尾部,但其仍有 due 日期,前端「已排期」桶(有 start **或** due 或有子)仍会把它画在主体区,只是位于该组兄弟的末尾。若期望按 due 排,需改拍板语义——默认按字面实现。
- 未排期组内部按 `number` 排(不按标题/状态)。
- 排序只动行序,不动任何数据;折叠/汇总条/今日线不受影响。

### 非目标
- 不改前端渲染逻辑(前端按 API 行序渲染;「真未排期=无日期且无子」的底部折叠组逻辑保持不变,与本票排序兼容:后端把无 start 的排后后,该桶自然收集到这些行)。
- 不改 scope 过滤、未排期折叠交互、日期换算(YPJ-235 逻辑)。
- 不做前端可切换排序(如按标题/状态)——拍板只有一种序。

## 二、详细设计(代码级)

### 改动 1:`backend/api/src/main/kotlin/yangzhou/api/business/project/ProjectService.kt`
现状(L274):
```kotlin
fun walk(parent: UUID?): List<GanttRow> = (byParent[parent] ?: emptyList()).sortedBy { it.number }.flatMap { item -> ... }
```

改法:顶层(或 companion)加纯比较器,`walk` 换用它:

```kotlin
/** YPJ-233(拍板 2026-11-18):同级 start 升序、未排期(无 start)靠后、同 start 按创建序兜底;每层递归生效。 */
internal val ganttSiblingOrder: Comparator<yangzhou.persistence.Item> =
    compareBy(
        { it.startDate == null },              // 未排期靠后
        { it.startDate ?: LocalDateTime.MAX }, // start 升序(未排期组内该键恒同,落回 number)
        { it.number },                         // 稳定兜底:同 start / 未排期组内按创建序
    )
```
`walk` 内 `.sortedBy { it.number }` → `.sortedWith(ganttSiblingOrder)`。

**否决的备选**:
- 前端排(API 序在前端重排)——否决:排序是领域口径,归属后端单一真相;前端还有未排期折叠桶依赖行序语义。
- `sortedBy { it.startDate ?: MAX }` 一行版——否决:丢失「未排期组内按 number」的确定性分组语义,读代码的人要推 MAX 哨兵的隐含含义;三分支比较器即文档。
- 在 GanttRow(已投影)层排——否决:depth/树结构在 Item 层,排完再投影会打乱父子遍历;在 walk 递归处排是天然正确的挂点。

### 改动 2:单元测试 `backend/api/src/test/kotlin/yangzhou/api/business/project/`(新建 `GanttRowOrderTest.kt`)
按 skill 单测优先:比较器是纯函数,直测,不碰 DB:
- start 升序 + 无 start 靠后;
- 同 start 按 number;
- 未排期组内按 number;
- due-only(有 due 无 start)落未排期组(断言字面语义,防未来误改)。
构造 `yangzhou.persistence.Item` 实例直接比较(不可变 data class,new 即可)。

### 改动 3:集成测试 `backend/api/src/integrationTest/kotlin/yangzhou/api/business/project/GanttApiTest.kt`
新增一个 `@Test`(extends AbstractApiTest,API 黑盒;**无新表,不动 cleanDatabase 清单**):
- 建项目,乱序建 4 个根 item:A(start 12-03)、B(无日期)、C(start 12-01)、D(start 12-02)——创建序故意与 start 序错开;
- C 下挂 2 个子(c1 start 12-05、c2 无日期,c2 先建),B 下挂 1 个子(b1 start 12-04);
- GET /projects/{key}/gantt,断言行序:`C, c2?——不对,c2 无 start 落 C 组尾` → 精确期望:
  `[C, c1, c2, D, A, B, b1]`(同级 start 升序;B 及其子组整体在尾部——**注意**:B 无 start 但有子,`walk(null)` 层 B 落尾部;b1 在 B 组内按 start 升序正常排)。
- 复跑既有三个测试确认无回归(它们断言树结构/字段,不断言行序,预计不受影响;若有 fixture 依赖创建序的隐含断言,修 fixture 而非放松断言)。

### 改动 4:`backend/script/api.http`
gantt 区块负例/顺序验证:现有区块若已有乱序建票样例则复用;补一节「行排序验证」(乱序创建→GET gantt→肉眼核序),按文件头「整文件按序跑」约定自含前置。

### 验证口径(DoD)
`gradle build`(单测绿)+ `gradle integrationTest --tests "...GanttApiTest"` 绿 + 全量 integrationTest(merge 前)+ e2e gantt 套件绿(无行序断言,预判零影响,跑一遍实锤)。

## 三、手工测试用例(用户 build/debug 阶段执行)

前置:dev 实例(3001/8081),用 YPJ 项目(甘特页数据现成)。
1. 打开甘特页,看板 scope:同一父下的兄弟按开始时间从早到晚排列;未排期行(无 start)在各组尾部;底部「未排期」折叠组行为不变。
2. 展开任一父票:子项在组内亦按 start 升序。
3. 同一天开始的两票:相对顺序稳定(创建早的在前);刷新后不变。
4. 只有 due 的里程碑票(若有):出现在其兄弟组尾部(⚠ 字面语义,若觉得应按 due 排,告知改拍板)。
5. 切 scope(group/milestone 多选)后序规则不变(过滤只改集合,不改排序)。

## 四、As-built(2026-11-19,PR #235 已合)

- 实现 = 设计,零偏差:顶层 `ganttSiblingOrder`(三分支比较器)+ `walk()` 换 `sortedWith`;单测 4 例、集成新用例 1 例(期望序 `[C, c1, c2, A, B, b1]`)均按 §二落地。
- 验证:单测 4/4;`GanttApiTest` 5/5;全量 `integrationTest` 绿(70 类);e2e 全量过 + spec §三 手工 5 例过(用户验收,含 due-only 落组尾复核点确认按字面语义)。
- 搭车:台账 A25(裸 bootRun 缺 jwt secret → 冒烟须 dev profile + 临时端口),与本票无耦合。
- 后续钩子:亚日窗放大(R2 深化)归 YPJ-241 统一设计,不在本票。
