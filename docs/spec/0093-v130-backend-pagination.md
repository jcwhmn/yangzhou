# 0093 — v130 表格视图后端分页(YPJ-130)

> 2026-11-01 立项。背景:表格前端拉全量(`GET /projects/{key}/items` → ItemDto[])后浏览器内存筛选/排序/分页;YPJ 已 124 item,不可扩展,且 YPJ-129(日期筛选)需要后端过滤缝。本票把表格页切到服务端分页,缝一次做对。

## 1. 需求

### 范围

- 新端点 `GET /api/projects/{key}/items/page`:服务端过滤 + 搜索 + 多列排序 + 分页
- 表格页(`table/page.tsx`)切换到新端点;翻页/页大小/筛选/排序/搜索全部驱动服务端查询
- 旧端点 `GET /projects/{key}/items` 与其它消费方(nav/overview/backlog/详情页/e2e helpers)**零改动**

### 非目标

- YPJ-129 日期区间参数(下票;本票把缝备好)
- nav/overview/backlog/详情页迁到分页端点
- 游标分页 / infinite scroll
- 派生列值(overdue/blocked/dueSoon)的 SQL 化——**值仍由 service 层对当页组装**(现行为不变),本票只把**排序**下 SQL

### API 契约(状态 × 参数 → 行为真值表)

| 参数 | 约束 | 行为 |
|---|---|---|
| `page` | int ≥ 0,默认 0 | 超末页 → 空 items + total 照实(不 400);< 0 → 400 |
| `size` | 白名单 20/50/100,默认 20 | 白名单外 → 400(fields 提示) |
| `query` | 可空 | 见过滤语义表 |
| `status` | 可空,=status 名精确匹配 | 值无匹配 → 空结果(不 400,与前端下拉一致) |
| `assignee` | 可空,=成员名精确;`__none__`=未指派 | 同上 |
| `priority` | 可空,=P0..P3 精确 | 同上 |
| `sort` | 可空,`field:dir` 逗号分隔,dir∈asc/desc,默认 `number:asc` | 字段不在白名单 → **400 fail-fast**(fields 提示) |

响应 envelope:`{ items: ItemDto[], total: long, page: int, size: int }`,ItemDto 复用现状(含服务端 overdue/blocked/dueSoon/sprintName/priority 等)。

### 过滤/排序语义(逐条镜像前端现行为,spec 0091 §4 搜索 + 本票核对)

**query 搜索**(子串、不区分大小写,命中任一即保留):`number` / `parentNumber` / `title` / `assignee` / `sprintName` / `attributes[].value`

**排序白名单**(SQL 映射,Kotlin 侧白名单拼装,非白名单字段永不进 SQL 字符串):

| 字段 | SQL 来源 |
|---|---|
| number | 数值列(现按 numeric 比较) |
| title / type | 列文本 |
| status | join statuses.name |
| assignee | join members.name(null 排最后,双向) |
| priority / startDate / dueDate / feasSignal / createdAt | 列 |
| sprint | join item 分属 sprint 组名(item_group_member → item_group) |
| overdue(派生) | `(i.due_date IS NOT NULL AND i.due_date < :today AND s.is_final = false)`;`:today` = `LocalDate.now(clock)` 作参数传入——**Clock 注入纪律不破**(spec 0051) |
| blocked(派生) | `CASE WHEN i.object_id IN (:blockedIds) THEN 1 ELSE 0 END`;`:blockedIds` 来自 `dependencyService.blockedItemObjectIds`(定义单点不镜像);空集合时替换恒假片段,防 `IN ()` 空列表 SQL 错误 |

null 值排序:与前端一致(null 排最后,双向)。文本排序用 PG 默认 collation,与浏览器 localeCompare 在中文/混合字符上可能有微小顺序差——接受(拍板点③)。

## 2. 详细设计(代码级)

| 文件 | 改动 |
|---|---|
| `persistence/repository/ItemRepository`(或就近仓储) | 两条 `@Query`:分页查询(`LIMIT :limit OFFSET :offset`,WHERE 组合 + 动态 ORDER BY)、count 查询(同 WHERE);全部走 @Query,无 JDBC client(ADR-0004) |
| `api/business/item/ItemService` | 新 `pagedList(key, page, size, query, status, assignee, priority, sort)`:解析校验参数(sort 白名单解析,非法抛业务异常 → GlobalExceptionHandler 400)→ 查页 + count → **仅对当页(≤size 条)复用现有 ItemCtx enrich**(overdue/blocked/dueSoon/attributes 等,不再全量)→ 组 envelope;新 `PagedItemsDto(items,total,page,size)` |
| `api/business/item/ItemController` | `@GetMapping("/projects/{key}/items/page")`;page≥0 用 Bean Validation,size 白名单在 service 校验 |
| `web/app/p/[key]/table/page.tsx` | fetch 换新端点;`{page,size,sorts,query,filters}` → 查询参数;翻页/页大小/筛选/排序/搜索变更 → (必要时 setPage(0))+ refetch;count 用 envelope.total;死页面锁/记忆视图/列顺序逻辑不动 |
| `backend/script/api.http` | 新端点按序号入区块;负例区补:非法 sort 字段 400 / size=15 400 / page=-1 400 |
| `web/e2e` | helpers 不动(list 端点不变);table 用例行为应不变;翻页用例补 count 断言 |

**否决备选**:旧 `GET /items` 直接改形状(5+ 消费方破坏)✗;同 URL 双形状 param 区分(OpenAPI/客户端混乱)✗;游标分页(百级规模 OFFSET 足够;`shortcut: OFFSET 深分页性能,千级以上再议`)✗;派生列排序留在前端当页排(跨页语义破碎)✗。

## 3. 手工测试用例(用户 build/debug 执行)

1. 默认 20/页;翻页、切 50/100,total 正确
2. 筛选(状态/负责人含 `__none__`/优先级)后翻页:筛选跨页稳定
3. 搜索各目标(编号/标题/负责人/sprint 名/属性值)后分页
4. 多列排序(含 overdue/blocked 派生列)跨页顺序稳定;翻向/移除键
5. 记忆视图恢复(带筛选/排序)自动带参查询
6. 刷新回第 0 页;空结果空态
7. 负例(curl):非法 sort 字段 / size=15 / page=-1 → 统一 400 形状

## 4. 拍板点(编码前过目)

1. **页码不持久**:刷新回第 0 页;视图保存筛选/排序,不含页码——OK?
2. **sort 非法字段 400 fail-fast**(而非静默忽略回默认)——OK?
3. **文本排序 PG collation**(与浏览器 localeCompare 微小顺序差)——接受?

## 5. As-built(2026-11-01,编码完成;待用户手测后 PR)

### 实现期关键转向(探针实锤,spec §2 的设计已按此落地)

- **Spring Data JDBC 对 @Query 忽略 Sort/Pageable**(探针 `ItemPageQueryProbeTest` 实锤:全量原序返回)→ 排序改为**静态骨架**:`ORDER BY` 白名单 13 字段 × 2 向 × 3 槽位全枚举(78 个 CASE 项,`Repositories.kt`);槽位空串恒不命中;`NULLS LAST` 双向;多键 >3 忽略多余(前端不可达路径)
- LIMIT/OFFSET 走具名参数(`:limit`/`:offset`),非法 size 白名单 400(page 越末页→空 items + 真实 total,不 400)
- `blockedIds` 空集合换 nil-UUID 哨兵,防 `IN ()` 语法错(Postgres 不接受空 IN 列表)
- 搜索 `query` 做 `%`/`_`/`\` 转义(对齐前端 `String.includes` 字面子串语义,`escapeLike`)
- `:today` 由注入 Clock 算好作参数传入 SQL,Clock 纪律不破(spec 0051)

### 连带修正

- **assignees 筛选下拉数据源**:原从 items 全量派生,分页后只剩当页值 → 改 `GET /projects/{key}/members` 成员池(table 页 load 时拉取)
- **死页面语义延伸**:items/page 拉取失败 = 表格无数据 → 同进 fatal 锁(YPJ-131 原则);对应 e2e 死页面用例拦截 glob `items*` → `items**`(否则匹配不到新端点)

### 验证

- `gradle build` + `gradle integrationTest` 全量绿;新增单测 `ItemPageSortTest`(解析/截断/转义)+ 集成 `ItemsPageApiTest`(切片/total/排序跨页/搜索/筛选/负例 400)+ `ItemPageQueryProbeTest`(SQL 骨架行为)
- e2e 全量 63 passed(table.spec 分页用例走后端分页)
- api.http:item 分页正例 2 条 + 负例 3 条
