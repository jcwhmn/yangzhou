# spec 0089 V47 YPJ-120 看板开始即入当前 sprint + status 进行态属性

票:YPJ-120。领域语义变更(schema),三决策点已拍板(2026-10-30):①当前 sprint = 取第一个(最早创建的 active,与前端 `activeSprints[0]` 先例同序)②服务端实现(全入口一致)③toast 轻提示(不打断操作)。

## 一、需求

### 范围
1. **自动挂载(服务端)**:item 从 start 态离开、目标状态为进行态、且当前无 sprint 时,自动挂到该项目**最早创建的 active sprint**;无 active sprint 或已有 sprint 时静默跳过;响应携带 `autoAssignedSprintName` 供前端 toast。
2. **status 进行态属性**:`status` 表新增 `is_in_progress`(始/终/进行三属性齐);Workflow CRUD 契约对称扩展;预置三态回填。
3. **前端 toast**:看板页与 item 详情页 PATCH 成功后,若响应带 `autoAssignedSprintName` → Snackbar「已自动加入 sprint X(不想进可手动移出)」。

### 非目标
- flow 页 workload 的排除法分组(`!isStart && !isFinal`)不动——那是图表分组语义,非「进行态」判定
- status 编辑 UI 若现状无 isStart/isFinal 入口,则 isInProgress 的编辑入口同样暂缓(契约先给;另票)
- CLI 不改(items move 响应回显 sprintName 已自然受益)
- 不做「移出提示」「批量」

### 语义判例(请复核)
- **触发窗口 = 离开 start 态且目标 isInProgress**。与既有「开工须有主」(V4-S3)同一窗口。`To Do → Done` 直跳(跨过进行态)**不触发**(目标非 isInProgress)——是否符合直觉请拍板。
- **回填只认预置名**:`UPDATE status SET is_in_progress = true WHERE name = 'In Progress'`(精确匹配预置三态名);自定义状态一律 false,由用户后续改。历史自定义状态若有「进行中」语义的列,不会被自动识别。

## 二、详细设计(代码级)

### 1. `V42__status_is_in_progress.sql`
```sql
ALTER TABLE status ADD COLUMN is_in_progress BOOLEAN NOT NULL DEFAULT false;
UPDATE status SET is_in_progress = true WHERE name = 'In Progress';
```
(无 CHECK 约束——boolean;实体主构造直接声明,遵守 persistence 约定)

### 2. `entities.kt` Status
+`val isInProgress: Boolean = false`。
**同步 entities.puml**(status 表加字段;AGENTS 规矩:改 schema 必须同步)。

### 3. Workflow 契约(workflow 包)
- `WorkflowService.StatusDto` + `isInProgress: Boolean`;`createStatus/updateStatus` 参数对称扩展(`isInProgress: Boolean?`,update 语义同 isFinal:`?: status.isInProgress`)
- `WorkflowController` 请求 DTO 对称加字段

### 4. ItemService(核心)
- `ItemDto` + `val autoAssignedSprintName: String? = null`(共享 DTO 可空字段,create/list/board 全部 null,向后兼容;仅 PATCH 触发自动挂载时非空)
- `update` 的 statusItemId 分支,`itemRepo.save` 与 activity/history 之后追加:
```kotlin
var autoSprintName: String? = null
if (oldStatus?.isStart == true && !status.isStart && status.isInProgress) {
    val rows = memberships.findByItemObjectIdIn(listOf(current.objectId!!))
    val hasLive = /* rows 中存在 group.status != "completed" 的行(同 assignItem 的 current 语义) */
    if (!hasLive) {
        val first = groups.findByProjectObjectIdOrderByCreatedAt(projectObjectId)
            .firstOrNull { it.status == "active" }
        if (first != null) {  // 沿用 create 内联挂载模式(YPJ-56):save + recordAdd 留痕,同事务
            memberships.save(ItemGroupMember(groupObjectId = first.objectId!!, itemObjectId = current.objectId!!))
            memberHistory.recordAdd(first.objectId!!, current.objectId!!, clock.instant())
            autoSprintName = first.name
        }
    }
}
```
- **不注入 SprintService**(其依赖 ItemService,注入即循环依赖);挂载内联,模式与 create 的 sprint 初始归属(YPJ-56)一致
- DTO 组装:update 的返回路径把 `autoSprintName` 传入(ItemDto 组装点实现时对齐;若 update 返回 `get(...)`,则 get 增加带默认值的重载参数)
- 边界天然安全:目标列 `status == "active"` 不可能为 completed(completed 拒指派的校验语义在此不适用);WIP 校验属状态列,与 sprint 无关,不受影响

### 5. 前端(web)
- `web/app/p/[key]/page.tsx:151` 与 `web/app/p/[key]/i/[itemId]/page.tsx:292` 两处 PATCH:响应取 `autoAssignedSprintName`,非空 → MUI Snackbar(基建已有,overview 页先例)文案「已自动加入 sprint「X」;不想进 sprint 可手动移出」;toast 后照常刷新看板
- (实现时若详情页结构与看板差异大,详情页 toast 可拆小步,但本票两处都要接)

### 6. 测试
- **集成(AbstractApiTest 黑盒,唯一 seam=REST)**:
  1. backlog item(Todo 态、无 sprint)PATCH 到 In Progress → 201/200 响应 `autoAssignedSprintName = 最早 active 名`,GET board 断言归属
  2. item 已挂 sprint(哪怕 planned)→ 开始动作不覆盖,字段 null
  3. 项目无 active sprint → 不挂,字段 null,item 仍无 sprint
  4. `To Do → Done`(目标非进行态)→ 不挂
  5. 多个 active sprint → 挂到 createdAt 最早者
- **E2E(web/e2e,常规用例)**:看板开始 backlog item → toast 出现 + item 出现在 sprint 列;回归:已有 sprint 的 item 开始 → 无 toast
- 手工测试用例见下节

### 7. CLI/脚本影响
- `yz items move`(走同一 PATCH)对无 sprint item 开始后会自动入 sprint——**预期行为**,确认行不额外打印(非目标);api.http 补负例/正例记录(改 REST 契约须同步,AGENTS 规矩)

## 三、手工测试用例(用户 build/debug 阶段)

1. dev 环境(web 3001):建项目(默认三态)→ 建 sprint A、B(按序)→ backlog 建 item → 看板/详情把它拖到 In Progress → **toast 出现**,item 落在 sprint A(最早创建)列
2. 手动把该 item 移出 sprint(backlog)→ 再次开始 → **不再自动挂**(已触发过?否——移出后无 sprint,会再次挂!预期:**每次开始都尝试挂**?本 spec 语义=「开始且无 sprint → 挂」,故会重新挂。如要"只挂一次"需历史判断,当前不设——请用户复核此条)
3. 删光 active sprint → 开始 backlog item → 无 toast、item 仍在 backlog
4. `To Do → Done` 直跳 → 不挂
5. CLI:`yz items move` 开始一个无 sprint item → web 上看到它入 sprint(全入口一致)
6. Workflow API:GET statuses 含 `isInProgress`;PATCH status 改 isInProgress 生效(可用 Scalar/api.http)

## As-built(2026-10-31,随 PR 提交)

### 实际改动
- `V42__status_is_in_progress.sql`(新):ALTER status + is_in_progress;UPDATE 回填(WHERE name='In Progress')
- `entities.kt` Status + isInProgress;**entities.puml 同步**
- Workflow 契约:WorkflowService.StatusDto/createStatus/updateStatus + Controller/CreateStatusRequest/UpdateStatusRequest 对称加 isInProgress
- ProjectService.StatusDto(项目详情的 statuses 组装,独立于 workflow 包)同步加字段——**两处 StatusDto 各自组装,漏一处即契约不一致,集成契约用例兜住**
- ProjectService.defaultStatuses:预置 In Progress 模板带 isInProgress=true(见坑 ②)
- ItemService:ItemDto.autoAssignedSprintName + update 状态分支尾部内联自动挂载(沿用 create 内联模式,避免与 SprintService 循环依赖)+ return copy 回带字段
- web:看板页 moveStatus 后 success Alert(6s 自灭可手动关);详情页 moveStatus 后 Typography 轻提示
- 测试:集成 ItemsAutoSprintApiTest 5 用例;E2E auto-sprint.spec.ts 2 用例(1 例 @smoke)
- api.http:区块 19(Workflow 状态进行态)

### 实证坑
1. **Flyway 版本号 `ls | tail` 字典序陷阱**:`ls migration | tail` 看到的 V7/V8/V9 是字典序尾部(V10 排在 V9 前),真实最大版本是 V41——首建 V10 撞车 WIP limit 迁移("Found more than one migration with version 10"),改名 V42。取最大版本号必须按数字排序。
2. **回填打在运行期种子上无效**:V42 的 UPDATE 在 Testcontainers fresh 库 UPDATE 0 行——预置 5 列不是迁移数据,是 ProjectService.create 运行期插入。正解:预置模板本身带 isInProgress=true(新项目天然正确),V42 只服务存量库(那边行已存在,UPDATE 命中)。语义分工:模板管新库,迁移管旧库。
3. **建 sprint 默认 planned,自动挂载找 active**——集成/E2E 都必须先 PATCH sprint 到 active(模拟用户「sprints move active」动作),否则静默跳过,断言全空。
4. **详情页 tsx 是 \r\r\n 病态行尾**(ed136b8 只归一了 Main.kt):edit 工具 LF 锚点匹配不上,改 python 字节级补丁(按病态行尾写回)。看板页 tsx 正常 CRLF。
5. headroom 搅坏 ctx_execute 脚本第 4 次实锤:python 内联补丁的变量插值被搅(缩进写成字面量,写坏 ItemService 后 git checkout 恢复)——长补丁一律 write 落脚本文件再执行,行号+内容双校验。
6. assertNull 不能断 JsonNode 字段(缺失/null 都返回 NullNode 对象)——用 `assertTrue(node.isNull)`。

### 验证
- 集成 ItemsAutoSprintApiTest 5/5;`gradle build` + `gradle integrationTest` 全量双绿;tsc 干净(main 既有 release.spec 3 错与本票无关)
- E2E auto-sprint 2/2(含 toast 文本与无 toast 回归)
- WorkflowServiceUnitTest 调用点随新签名补 isInProgress 参数(既有单测,同步修)
