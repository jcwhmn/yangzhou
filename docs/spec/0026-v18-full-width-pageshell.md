# 0026 — YPJ-30:全站页面统一全宽靠左(PageShell)

> 2026-10-13 立项。状态:**草稿,待用户过目**。As-built 编码完成后补。

## 需求

**背景**(票 grill 定稿):全站页面统一**全宽靠左**,board/overview 现状即基准;其余页面散落 13 处 `Container maxWidth`(实际 sm/md/lg 混用)+ item 详情页 `maxWidth:1100 mx:auto`;要求共享布局组件承载,将来变更一处改。

**范围**
- 14 个 `<Container maxWidth>` 实例(13 个页面容器 + sprint 详情页 loading 态),分布:home/attributes/capabilities/members/notifications/recycle-bin/search/standup/time/backlog/gantt/table/sprint/[id](×2)
- item 详情页(`p/[key]/i/[itemId]/page.tsx:402`)`<Box sx={{ maxWidth: 1100, mx: "auto", p: 3 }}>`
- 新增共享组件 `PageShell`

**非目标**
- **不动 MUI Dialog 的 `maxWidth="sm"`**(详情页 928/1042 两处)——弹窗语义,非页面容器;验收「无 `Container maxWidth` 残留」不涉及 Dialog。
- 不改导航、不改页面内部布局、不引入响应式断点(基线页就是 `p: 3` 全局一致)。

## 详细设计

**新增 `web/components/PageShell.tsx`**(default export,对齐组件目录惯例):

```tsx
import type { ReactNode } from "react";
import { Box } from "@mui/material";

/** 全站页面容器:统一全宽靠左(YPJ-30)。布局将来变更只改这里。 */
export default function PageShell({ children }: { children: ReactNode }) {
  return <Box sx={{ p: 3 }}>{children}</Box>;
}
```

- padding 取 `p: 3` = **board 基准页现值**(`p/[key]/page.tsx:157`),全站一致。
- 否决备选:①保留 Container 仅去 maxWidth——Container 自带居中语义,与验收冲突;②响应式 padding——基线没有,不加投机。

**替换(机械替换,15 个文件)**
- `<Container maxWidth="…" sx={{ py: … }}>` → `<PageShell>`;`</Container>` → `</PageShell>`。
- sprint/[id] loading 态(py:10)→ PageShell(p:3):加载文案上移,闪现态,可接受。
- 详情页 1100 居中 Box → PageShell(p:3 与原值仅 px 差异)。
- 各页 import:删 `Container`(named),加 `PageShell`(default)。

**验收映射**
- 「全站无 Container maxWidth 残留」→ `grep -rn "Container maxWidth" web/app` 0 命中
- 「e2e 全绿」→ web/ 标准跑法全量(布局改动一般不影响断言;table/gantt 全宽后溢出风险由人工巡检覆盖)

## 手工测试用例(build/debug 阶段)

1. 全站巡检:13 页 + 详情页 + board/overview 宽度观感一致(全宽靠左、padding 一致)。
2. 窄窗口(<960px)各页不横向溢出。
3. sprint 详情 loading 态正常闪现。
4. e2e 全量:web/ 下 `E2E_FRONT_PORT=3001 E2E_BASE_URL=http://localhost:3001 E2E_API_URL=http://localhost:8080 BACKEND_URL=http://localhost:8080 npx playwright test`(8080 = test profile)。

## 验收对照(票)

- [ ] 全站无 `Container maxWidth` 残留 → 设计验证 grep + 手工 1
- [ ] e2e 全绿 → 手工 4

## As-built

> 2026-10-13 完工。与设计零结构性偏离,纯机械替换。

**实际落地**

- `web/components/PageShell.tsx`(新):named export(对齐 `@/components` 惯例),`<Box sx={{ p: 3 }}>` = board 基准页现值。
- 14 个 `<Container>` 实例 → `<PageShell>`(13 页面容器 + sprint 详情 loading 态);详情页根 Box(1100 居中)→ `<PageShell>`,闭合锚 = 根级 `</Box>`(909 行,`function RequirementsDialog` 前,唯一)。
- import 两种形态分别处理:独行 `  Container,`(6 文件)删行;行内 ` Container, `(7 文件)带空格替换——天然避开 `TableContainer` 子串陷阱;均于 `} from "@mui/material";` 后插 `import { PageShell } from "@/components/PageShell";`。
- Dialog `maxWidth="sm"` 两处未动(按设计)。

**编码期发现**

- Container 实际形态比票面「lg」多(sm/md/lg × py:4/py:10 组合);首版枚举法漏 `sm+py:4`,改结构化定界替换(`<Container` 起至最近 `>`)一次覆盖全部形态。

**验证**

- `grep -rn "Container maxWidth" web/app` = 0;`<Container`/`</Container>` 残留 = 0
- `npx next build` 绿;e2e 全量 **37 passed(1.5m)**(8080 test profile + prod webServer)
