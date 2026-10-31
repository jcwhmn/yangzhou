# 0091 — V26 表格视图增强(YPJ-99 及子票 YPJ-123~127)

> 2026-10-31 立。背景:用户使用项目表格视图(`/p/[key]/table`)反馈 6 个问题,经研究定位根因后拍板需求与范围。
> 母 spec:按子票分节,每节含需求/详细设计/手工测试用例;子票完工后逐节补 As-built。

## 0. 总览与决策

### 用户反馈(2026-10-31)

1. 编号按字符序排(1,10,100,2…),应按数字排
2. 排序按钮放列头,支持多栏排序
3. 分页显示
4. 状态栏内容为空
5. Sprint 栏大部分内容为空
6. 表格上方搜索条,可全文搜索

### 研究结论(根因)

- **#1**:前端 `localeCompare` 纯字符串比较;后端 `findByProjectObjectIdAndDeletedAtIsNullOrderByNumber` 本就按 int 数字序返回,是前端二次排序破坏
- **#4**:单元格渲染三元链**缺 `status` 分支**,落到兜底 `""`(数据存在,纯前端渲染 bug)
- **#5**:`sprintName = null`(backlog item)渲染空串;属数据事实,缺视觉标识
- **#6**:已有全局搜索 `GET /api/search?q=`(ILIKE 标题/描述/编号,spec 0052);表格本身无搜索
- 视图(View,spec 0045)仅存 filters + columns,不含排序/搜索词
- 数据规模:dogfood 项目 113 items,客户端过滤/排序/分页无压力

### 用户拍板(2026-10-31)

1. **搜索两步走**:本期只搜可见字段(客户端,零 API 变更);**描述全文搜索另立 YPJ-122(backlog,待办)**
2. **视图保存**:内容包括列、排序项、搜索条件;命名机制沿用现有
3. **旧排序下拉**:删除(列头排序取代)

### 拆票(垂直切片,各自独立合并、验证绿)

| 子票 | 内容 | 依赖 |
|---|---|---|
| YPJ-123 | 显示修复(status 渲染 / sprint backlog 标识 / 编号自然排序) | 无 |
| YPJ-124 | 列头多栏排序,删旧下拉 | 无 |
| YPJ-125 | 分页 + 表头吸顶 + 加载态 | 无 |
| YPJ-126 | 表内搜索条(客户端) | 无 |
| YPJ-127 | 视图保存 sort + query(后端契约 + 前端接入) | 124、126 |

父票 YPJ-99(In Progress)随子票收口;子票全 Done 后父票 Done。

### 非目标(明确不做)

- 描述全文匹配 → YPJ-122(需 items 列表响应加 description 或项目内搜索端点,契约变更)
- 全局搜索结果表格化 + 与 export 打通(YPJ-99 票内 2026-10-27 旧方向注记)——本期以本 spec 为准,该方向是否另立票由用户定
- 服务端分页/排序(数据量不需要;若未来项目过万 item 再议)
- 筛选/排序/搜索/页码的 URL 同步(deep-link)
- 状态列着色、列宽拖拽、列顺序调整

---

## 1. YPJ-123 显示修复

### 需求

- 状态列显示 item 状态名(数据已在响应 `status` 字段,补渲染分支)
- Sprint 列 `sprintName = null` 时显示弱化文字 `backlog`(灰色、半透明),与空字符串区分,一眼可辨"未入冲刺"
- 编号排序改为自然数字序:`YPJ-1, YPJ-2, …, YPJ-10, YPJ-100`(localeCompare `numeric: true`);作为 priority/status 排序的次级兜底键时同样适用

### 详细设计

- `web/app/p/[key]/table/page.tsx` 单文件:
  - 渲染链加 `c.id === "status"` 分支 → `{it.status}`
  - `c.id === "sprint"` 分支:`it.sprintName ?? <弱化 backlog>`
  - 排序兜底键:`a.number.localeCompare(b.number, undefined, { numeric: true })`
- `web/lib/texts.ts` 加 `t.view.backlog = "backlog"`(文案外置)
- E2E:`web/e2e/table.spec.ts` 新建,@smoke:表格加载后状态列非空、编号行序为数字序(取前 N 行断言单调递增)

### 手工测试用例

1. 打开任一有 item 的项目表格 → 状态列每行有状态名
2. Sprint 列:未挂 sprint 的行显示灰色 `backlog`,挂了的行显示 sprint 名
3. 观察编号列排序(默认):1,2,…,9,10,…,100 严格数字序
4. 旧下拉切「按优先级」→ 同优先级内编号仍数字序

---

## 2. YPJ-124 列头多栏排序

### 需求

- 每个可见列的表头可点击:单击循环 **升序 → 降序 → 取消**;取消后若排序键空则回默认(`number` 升序)
- **Shift + 点击**:将该列**追加**为最低优先级排序键(已在该键则按单击循环处理)
- 多栏时表头显示优先级序号(1、2、…)+ 方向箭头(MUI TableSortLabel 自带)
- 删除工具条旧「排序」下拉(功能被完全取代,用户拍板)

### 详细设计

- state:`sorts: { field: string; dir: "asc" | "desc" }[]` 取代 `sort: string`
- 表头:`TableSortLabel` + `active`/`direction`;多栏序号用小号 Typography 叠加在 label 后
- 比较器:按 `sorts` 数组序逐一比较,全等回 0;各字段比较器:
  - `number`:`localeCompare(..., {numeric: true})`
  - `title` / `assignee` / `sprint` / `status`:`localeCompare`,null 排最后(双向)
  - `priority`:`P0<P1<P2<P3` rank,null 最后
  - `startDate` / `dueDate`:ISO 字符串比较,null 最后
  - `overdue` / `blocked`:false < true
  - `feasSignal`:GREEN < YELLOW < RED,null 最后
- 默认 `sorts = [{ field: "number", dir: "asc" }]`

### 手工测试用例

1. 点「编号」表头:升 → 降 → 取消(回默认升序)
2. 点「标题」表头 → 按标题字典序;再 Shift+点「负责人」→ 标题相同行内按负责人排
3. 多栏时表头显示 1/2 序号与方向箭头
4. 旧「排序」下拉不再出现
5. 排序与状态/负责人/优先级筛选叠加正确

---

## 3. YPJ-125 分页 + 表头吸顶/加载态

### 需求

- `TablePagination`:每页 20/50/100 可选,默认 20;页大小写 localStorage(`yz-table-page-size`,全局单键,属用户偏好非项目数据)
- 筛选、排序、搜索任一变更 → 回第 1 页
- 表头吸顶:`TableContainer` 设 `maxHeight`(约视口剩余高度)+ `stickyHeader`
- items 加载中显示 `LinearProgress`(现为静默空白)

### 详细设计

- `page` / `rowsPerPage` state;`rowsPerPage` 初始读 localStorage,变更写回
- `paged = sorted.slice(page * rowsPerPage, …)`;行数变化(筛选后)越界时 `Math.min(page, maxPage)` 兜底
- `changed()` 辅助:筛选/排序/搜索 setter 内统一 `setPage(0)`

### 手工测试用例

1. >20 行项目:默认显示 20 行 + 分页条(共 N 行);翻页/跳页正常
2. 切每页 50 → 刷新页面后仍为 50
3. 输入搜索词/切换筛选/点列头排序 → 自动回第 1 页
4. 纵向滚动时表头固定可见
5. 刷新页面瞬间可见加载进度条

---

## 4. YPJ-126 表内搜索条

### 需求

- 表格上方(筛选行同排)加搜索框(带搜索图标):不区分大小写子串匹配
- 匹配范围:`number`、`parentNumber`、`title`、`assignee`、`sprintName`、自定义属性 `attributes[].value`
- 空查询 = 不过滤;与三个筛选下拉、排序、分页正交叠加
- **不含 description**(两步走,YPJ-122)

### 详细设计

- `query: string` state;谓词 `matches(it, q)`:q 小写后对各字段 `includes`(attributes 先 `Item` type 加 `attributes: { name: string; value: string | null }[]`——后端本就返回,补类型)
- 客户端即时过滤,无 debounce(113 行量级无压力)
- 文案外置:`t.view.searchPlaceholder = "搜索编号、标题、负责人…"`

### 手工测试用例

1. 搜「视图」→ 标题含"视图"的行;搜 `ypj-12`(小写)→ 命中 YPJ-12x
2. 搜某负责人名 → 该负责人全部行;搜某 sprint 名 → 该 sprint 全部行
3. 搜自定义属性值(如某词表标签)→ 命中对应行
4. 清空 → 全量恢复;与状态筛选叠加取交集
5. 搜索时翻到第 2 页再输入新词 → 回第 1 页

---

## 5. YPJ-127 视图保存排序与搜索条件

### 需求

- 视图保存内容扩为:**columns + filters + sorts + query**(可命名、可另存、可删,机制沿用 spec 0045)
- 应用视图:四者全部恢复;切回默认视图:恢复默认(排序 number 升、搜索清空、筛选清空、列配置回 localStorage)
- 用户 × 项目私有语义不变;未选视图时 sorts/query 为会话态不入库

### 详细设计

**后端(契约变更:additive,可空字段)**

- 迁移 `V43__view_sort_query.sql`:`project_view` 加 `sort TEXT NULL`、`query TEXT NULL`(命名约束,无 NOT NULL)
- `ProjectView` 实体加两可空字段(主构造声明,objectId/createdAt/updatedAt 惯例不变)
- `ViewService`:`ViewDto` 加 `sort: JsonNode? = null`、`query: String? = null`;Create/Update Request 加可空同名字段;存储为字符串、`toDto` 读回;PATCH 不传不改(与 filters/columns 一致);sort/query 后端**透传不解释**(与 filters 同法,客户端契约)
- `backend/script/api.http`:views 区块补 sort/query 示例字段

**前端**

- `ViewDto` 类型加 `sort?: { field: string; dir: "asc"|"desc" }[] | null`、`query?: string | null`
- `saveCurrent`:body 加 `sort: sorts`、`query: query || null`
- `applyView`:`setSorts(v.sort ?? 默认)`、`setSearch(v.query ?? "")`
- dirty 判定纳入 sorts/query

**否决的备选**:把 sort/query 塞进 filters JSON 袋(零后端改动,但语义撒谎——filters 袋混入非筛选项,弃);单列 `state` TEXT 存合并 JSON(过度设计,弃)。

### 测试

- 集成(REST 黑盒,`src/integrationTest`):建视图带 sort+query → GET 回读一致;PATCH 仅改 sort → query 不变;**写前读 backend-testing skill**(表清单同步 cleanDatabase——`project_view` 若未入清单需补)
- E2E:`view.spec.ts` 存量用例补断言(保存带排序+搜索词的视图 → 切默认 → 切回恢复)

### 手工测试用例

1. 设排序(标题升)+ 搜索词 + 隐藏两列 → 保存为「我的视图」→ 切默认 → 切回:排序/搜索/列配置全部恢复
2. 「保存」就地更新当前视图;另存新视图互不影响
3. PATCH 语义:仅改列配置再保存 → 排序/搜索词不丢
4. 另一账号看不到该视图(私有语义回归)

---

## As-built(完工后补)

- (待各子票完工逐节补)
