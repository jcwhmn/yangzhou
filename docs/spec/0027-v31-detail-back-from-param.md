# 0027 YPJ-31:详情返回 from 参数(多入口 fallback 看板)

2026-10-13 立项。状态:**完工**(As-built 见文末)。

## 需求

**背景**(票 grill 定稿):详情页返回链现写死 `/p/{key}`(「← 返回看板」),从任何入口进都回看板。要求:

- 通用 `?from=` 参数:入口页跳详情时携带,返回链指回来源页。
- 覆盖入口:**backlog / sprint / table / gantt / 搜索**(看板本就同页,无需 from)。
- 无 from 或非法 from:**fallback 看板**。
- 方案裁决:A(from 参数)而非 `router.back()`——直达 URL 时 back 会退到站外,确定性优先。

**非目标**

- home / notifications / standup / 项目侧栏 epic 链接(`p/[key]/layout.tsx`)不在五入口内,保持 fallback 看板;白名单机制天然支持将来扩展。
- 不把搜索词带进 URL:搜索页 query 是本地 state,`from=search` 只回 `/search`,搜索词丢失(可接受;要保留需搜索页先 URL 化,另票)。
- 不动 router.back / history 语义;不动看板入口(现行为已正确)。

## 详细设计

### 1. from 取值空间 = 白名单 token(防开放跳转)

| from | 回跳目标 | 标签 |
|---|---|---|
| `backlog` | `/p/{key}/backlog` | ← 返回 Backlog |
| `table` | `/p/{key}/table` | ← 返回表格 |
| `gantt` | `/p/{key}/gantt` | ← 返回甘特图 |
| `sprint-{sprintId}` | `/p/{key}/sprint/{sprintId}` | ← 返回 Sprint |
| `search` | `/search`(全局页,不在项目下) | ← 返回搜索 |
| 无 / 不匹配 | `/p/{key}`(fallback) | ← 返回看板(现文案不动) |

校验:整体正则 `^(backlog|table|gantt|search|sprint-[0-9a-zA-Z-]{1,64})$`。token 只由本应用生成;字符集不含 `/`、`.`、`:`,排除路径穿越与 `//` 协议相对 URL;回写只进 `<Link href>`(Next 客户端路由),无开放跳转面。

### 2. 代码修改计划(web only,后端零改动)

- **新增 `web/lib/back.ts`**:`resolveBack(from: string | null, projectKey: string): { href: string; label: string }` 纯函数——内联白名单正则 + 映射表。web 无单测跑器(测试基建是 YPJ-38),该函数靠手工用例 + e2e 兜底。
- **`web/app/p/[key]/i/[itemId]/page.tsx`**:
  - 读参**不用 `useSearchParams`**(规避 next build 静态化的 Suspense 边界要求):`useEffect` 中 `new URLSearchParams(window.location.search).get("from")`,过 `resolveBack` 后存 state。
  - L404 返回 `<Link>`:`href` 改 `back.href`,文案改 `back.label`(原 `t.item.back` 保留为 fallback 值)。
  - L512 删除成功后的 `window.location.href = /p/${key}` 同步改指 `back.href`(同一"离开详情页回来源"语义;**本点为 agent 拍板,请复核**——票面只说返回链,删除是第二返回路径)。
- **文案外置 `web/lib/texts.ts`**:item 区新增 `backTo` 映射(backlog/table/gantt/sprint/search 五个中文标签),`back` 不动。
- **入口页机械追加 `?from=`**(现详情链接均无 query,直接模板串拼接):
  - `p/[key]/backlog/page.tsx`(2 处 Link)→ `?from=backlog`
  - `p/[key]/table/page.tsx`(2 处 Link)→ `?from=table`
  - `p/[key]/gantt/page.tsx`(1 处 Link)→ `?from=gantt`
  - `p/[key]/sprint/[id]/page.tsx`(3 处 Link)→ `?from=sprint-${id}`
  - `search/page.tsx`(1 处 Link)→ `?from=search`

否决的备选:`router.back()`(票面已裁决否);完整来源 URL 直传 + 域名校验(校验逻辑比白名单贵,收益为零);`useSearchParams` + Suspense 包装(为读一个参数重构 1100 行页面结构,不值)。

## 手工测试用例(build/debug 阶段)

1. **五入口正向**:backlog / table / gantt / sprint / 搜索 各点一条 item 进详情 → 返回链标签分别为「← 返回 Backlog / 表格 / 甘特图 / Sprint / 搜索」,点击回到来源页。
2. **非法 from fallback**:详情 URL 手改 `?from=xxx`(及 `?from=//evil.com`、`?from=sprint/../../x`)→ 返回链为「← 返回看板」,点击回看板。
3. **无 from**:看板点 item → 返回链「← 返回看板」,行为与现状一致。
4. **删除跟随来源**:从 gantt 进详情后删除该 item → 落回 gantt 页。
5. **构建**:`web/` 下 `npx next build` 通过(无 useSearchParams/Suspense 报错)。
6. **回归**:e2e 全量(改的是 Link href,预期无断言影响)。

## 验收对照(票)

- [x] 五入口返回来源页 → 代码落地 + e2e(sprint/table/详情导航)全绿;逐条 UI 手测未留痕,合并前建议抽验一条非法 from
- [x] 无 from/非法 from fallback 看板 → resolveBack 白名单,代码保证
- [x] tsc 零新增错(release.spec 3 错 main 既有);e2e 全量 37 passed(用户实跑)

## As-built

> 2026-10-13 完工。与设计零结构性偏离,纯机械落地。

- `web/lib/back.ts`:`resolveBack(from, projectKey)`,白名单正则 `^(backlog|table|gantt|search|sprint-[0-9a-zA-Z-]{1,64})$` + 5 映射,与设计一致。
- 详情页:`useState(() => resolveBack(null, key))` + `useEffect` 读 `window.location.search`(未用 useSearchParams,规避 Suspense 边界);返回 Link 与删除后跳转均走 `back.href/label`。
- 入口页 9 处 Link 追加 `?from=`:backlog ×2 / table ×2 / gantt ×1 / sprint ×3(`?from=sprint-${id}`)/ search ×1。
- 验证:`tsc --noEmit` 零新增;e2e 全量 37 passed(标准 env,用户实跑)。排障记录:一次裸跑报错系环境问题(行内 env 不残留 → 回落 `.env` 的 3000 → 复用旧 dev server),非代码,已列入 session 知识。
