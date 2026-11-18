# 0141 — YPJ-220 甘特 S3:范围过滤

> 状态:设计稿(待用户过目)。关联:YPJ-220(父 YPJ-217);PRD §二R1;依赖 219 v2 轴(数据锚定,本票不改轴)。
> 术语纪律(2026-11-18 用户定):文档一律用 **item group / group**,不用 sprint;sprint 只是 group 的一种 type。

## ⚠ 契约变更显要标注

`GET /api/projects/{key}/gantt` **缺省范围从「全部 item」改为「看板」**(2026-11-18 用户拍板,推翻票面原「缺省=全部」)。既有调用方(api.http、e2e、未来 CLI)若依赖全量语义必须显式传 `scope=all`。已发布语义解释变更,置顶标注。

**group 多选**(2026-11-18 用户补充):`scope=group` 时传 `scopeIds`(逗号分隔 UUID,可多个,命中并集);兼容单值。

## 一、需求

### 范围(scope)

甘特数据源支持按 item 集合过滤,五选:

| scope | 集合定义(实锤自代码) | scopeId |
|---|---|---|
| `board`(缺省) | 复用看板语义(ItemGroupService.board,spec 0098/0117):active 一级组成员并集;**无 active group → 非 backlog 全量** | 无 |
| `group`(可多选) | 各 group 成员并集(item 只挂叶层组;**非叶层组 = 叶子后代组成员并集**,父层组可选=选整棵子树) | group objectId 列表(逗号分隔) |
| `milestone` | milestone_item 挂接的 items | milestone objectId |
| `release` | release_items 挂接的 items | release objectId |
| `all` | 全部 item(现行为;大项目几乎不用,保留作兜底) | 无 |

### 树形语义(2026-11-18 用户拍板)

- 命中集 S 解析后,显示集 = **S ∪ S 的全部后代(跟随显示) ∪ 上述各点的祖先链**(祖先链不被过滤截断,PRD R1)。
- 树序、行字段、汇总条/置灰/blocked 等渲染一概不变——只改行集合。

### 前端

- 页头范围选择器:scope 五选 + 二级对象选择(group/milestone/release 时列出项目内对应集合,按现有列表 API);group 为**多选**(MUI Autocomplete multiple);board/all 无二级。
- **工况跨页保持**(sessionStorage `gantt.view`,与 219 同键):scope/scopeIds/缩放/视点日/滚动位——甘特→其它页→回甘特,原样恢复。
- **保存视图(服务端,可复用)**:复用 ProjectView(2026-11-18 用户拍板「应可保存、以后复用」):
  - `filters` JSONB 载荷 `{ kind:"gantt", scope, scopeIds, zoom, center }`(ProjectView 无 type 列,用 kind 字段区分,不动 schema);按创建者隔离现成;
  - 甘特页头增:视图下拉(仅列 kind=gantt)+「保存」(当前工况存为具名视图,同名覆盖)+「删除」;
  - 会话内最后使用的视图 id 记 sessionStorage,进页自动套用。
- 过滤后零 item → 沿用现有空态文案。
- **无 due 不外延到轴末端**(2026-11-18 手工反馈,223/224/225 长黑线案):条宽 right 修为 due 空 = 起点(due-only 菱形不变);「start+estimate 推条长」另票。
- **父自有日期不参与轴锚定**(2026-11-18 手工反馈,YPJ-208 实锤案):axis 日期收集与画条同口径(spec 0139 父条语义)——父行取子孙排期并集,子行取自有日期;否则父残数据把轴撑远而无可见出处。
- **行标题列可点进 item 详情**(2026-11-18 手工反馈补):RowTitle 的标题包 Link(`/p/{key}/i/{itemId}?from=gantt`),条形区既有整块覆盖链接不变。

### 其它候选集合(用户问「还有其它吗」)

本票不做、留扩展位:`scope` 枚举开放,后续可加「指派给我」「未完结」「blocked 子图」等(集合源均为纯函数可插)。无表结构代价。

### 非目标

- 不改 v2 轴逻辑(范围过滤后数据锚定轴自动收缩,天然联动)。
- 不做 219 的显示问题回归(219 挂起,回归时用本票缺省 board 范围验证)。

## 二、详细设计(代码级)

### backend

1. `ProjectController.gantt` 增参:`@RequestParam(defaultValue="board") scope: String`、`scopeIds: String?`(逗号分隔,scope=group 时用;`scopeId` 单值不再单独提供,统一走 scopeIds)。
2. `ProjectService.gantt(key, scope, scopeId)`:
   - 注入 `ItemGroupService`(board 集合解析复用其逻辑,抽包内可见方法或注入同批 repo;倾向:ItemGroupService.board 拆出 `boardItemObjectIds(projectId): Set<UUID>` 供两处调用);
   - `resolveScope(projectId, scope, scopeId): Set<UUID>`(命中集):
     - `board` → 上述方法(无 active → 非 backlog 全量,复用现有 board 分支);
     - `group` → 逐个 scopeId 校验(404)→ 叶组直取 memberships;非叶组递归收集叶后代组成员并集;多选取并集;
     - `milestone`/`release` → 各自 404 校验 → `milestoneItems`/`releaseItems` 按 milestoneObjectId/releaseObjectId 取 itemObjectId 集;
     - `all` → 全集;未知 scope → 400。
   - 过滤:`val matched = all.filter { it.objectId in S }`;显示集 = matched + 后代(matched 的 objectId 沿 parentObjectId 向下收集)+ 祖先(effectiveParent 链向上收集);集合求并后仍按现有 `walk` 树序输出(实现:先算 keep 集合,`walk` 内跳过不在 keep 的分支)。
3. DTO 不变(GanttRow 字段零改动)。
4. `backend/script/api.http` gantt 区块补 5 个 scope 变体 + 负例(坏 scope、坏 scopeId)。

### web

5. `web/app/p/[key]/gantt/page.tsx`:
   - state:`scope`/`scopeIds`(sessionStorage `gantt.view` 增 `scope`/`scopeIds` 字段,旧值缺省补 `board`);
   - `load` 带 query;scope 下拉 + group 多选(MUI Autocomplete multiple)/milestone/release 单选下拉,列表经现有 API:`GET /api/projects/{key}/groups`(组树,叶/父都可选中)、`GET /api/projects/{key}/milestones`、`GET /api/projects/{key}/releases`;
   - scope 切换时清空二级选择;load 依赖 scope/scopeIds;
   - 保存视图:调 `POST/PUT /api/projects/{key}/views`(filters=`{kind:"gantt",...}`),视图下拉调 `GET /api/projects/{key}/views` 过滤 kind=gantt,套用 = 恢复载荷进 state。
6. e2e(gantt.spec 增 1–2 例,唯一 key 项目):造 2 组——active 组(A1 成员 x,A2 成员 y)、milestone 挂 z;断言缺省=board 只见 x/y;z 用 milestone scope 可见;all 可见全部;group 多选两叶组并集;x 的父(未命中)作为祖先链出现;保存视图→切页→回来→套用视图恢复范围;负例 UI 不测(API 层 api.http 兜)。

## 三、手工测试用例(用户 build/debug 阶段)

1. 缺省打开甘特(项目有 active group):只显示 active 组成员 + 祖先 + 后代;URL 无参数。
2. 切 group 范围:多选两个叶层组 → 两组成员并集都在;选父层组:整棵子树成员都在。
3. 切 milestone 范围:只见挂接 items 树;release 同理。
4. 切「全部」:回到全量(219 现象复现,预期内)。
5. 刷新页面:范围选择保持;切范围后轴区间随之收缩;甘特→看板→回甘特:范围/缩放/视点原样恢复。
7. 保存视图:存「V31 范围」→ 切到全部乱看 → 视图下拉选回 → 范围/缩放恢复;同名再存 = 覆盖;删除视图后下拉消失。
8. 无 active group 的项目:缺省 = 非 backlog 全量(与看板根页一致)。
9. 负例(api.http):scope=foo → 400;scopeIds=随机 UUID → 404;scope=group 无 scopeIds → 400。

## 四、As-built(2026-11-18)

- 后端:契约变更(缺省 board)按 §一落地;`ItemGroupService.boardItemObjectIds/groupMemberItemObjectIds`、`ProjectService.resolveScope`(keep=命中∪后代∪祖先链,树序不变)与设计一致;GanttApiTest 新用例组覆盖 board 祖先链/group 并集/milestone/all/负例,集成绿。
- 前端(gantt/page.tsx):scope 五选 + group 多选(disableCloseOnSelect)/milestone/release 单选,未选完不发请求;工况持久化改为 state 变化即落盘(scope/scopeIds/zoom/center/scroll),并加 load 序号守卫(过期响应丢弃);轴锚定父行取子孙并集(忽略父自有日期);无 due 条不外延到轴末端(起点小标记);行内/未排期桶标题包 Link 进详情。
- 过程修复三案(A13 台账):MUI 多选 closeOnSelect、工况只在滚动落盘、慢响应覆盖新范围;外加 MUI Menu 淡入期吞点击(e2e pickScope 等 300ms)。
- e2e:gantt.spec 11 例(新 5:范围过滤/工况跨页/标题链接/父不撑轴/无 due 小标记)、board.spec 14 例(2 例切 scope=all),全绿。
- 手测反馈处置:12-03 出处→实锤父残日期撑轴(修)+208 数据疑点(留用户清);227 扩展已有票;排序/缺省视窗/estimate 推条长立新票。
