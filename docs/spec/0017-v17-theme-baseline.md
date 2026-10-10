# 0017 — V17-4 theme 基线:清爽风格设计令牌 + 全局去下划线

> 票:YPJ-17。第一个 UI sprint 的地基:建立设计系统基线(方向 = 清爽、淡蓝/绿背景、黑字、细线、紧凑不拥挤、有层次),V17-5/6 组件票在其上做。

## 1. 需求(详细)

### 1.1 背景

- `web/components/Providers.tsx` 现 23 行:只有字体 + V11-Q3 的「全局 variant=standard」(当时为了去边框,副作用 = 满屏下划线输入框)。无色彩、间距、层次体系。
- 本票反转 V11-Q3 决策:outlined 全局,配成体系的设计令牌。

### 1.2 目标

1. **设计令牌定稿**(`web/lib/tokens.ts` 新文件):背景(淡蓝/淡绿两版,截图供用户取舍)、文字(近黑)、边框(1px 细线)、间距密度(紧凑不拥挤)、层次(elevation/分割线)。
2. **Providers.tsx 落地**:23 行现状 → 消费 tokens 的成体系 theme(palette/shape/spacing/typography/components)。
3. **下划线全灭**:`MuiTextField`/`MuiFormControl` 默认 `variant: "outlined"`——已核实代码内零显式 `variant="standard"/"filled"`,一处默认翻转即全局生效。
4. **两版截图走查**:全页截图(约 17 页 × 2 背景)交用户审美确认,选定后收敛为常量定稿。
5. **E2E 全量本地绿**(用户执行,标准跑法)。

### 1.3 非目标

- 不做暗色模式;不引 UI 库/主题包。
- 不改页面布局结构与信息架构;不动 V17-5(dropdown)/V17-6(日期)组件票内容。
- **不启用全局 `density: "compact"`**(行高全变,破坏面大且 e2e 点击回归风险高)——「紧凑」靠间距收紧与页面留白,不靠压行高。
- 不重截/重设计单个页面,只做全局基线。

### 1.4 验收(票内清单)

- [ ] 设计令牌定稿:背景色(淡蓝/绿两版截图供取舍)、文字、边框粗细、间距密度、层次(elevation/分割线)
- [ ] Providers.tsx theme 落地(palette/spacing/border/elevation,23 行现状 → 成体系)
- [ ] MuiTextField 默认 variant=outlined → 下划线全灭(一处改动全局生效)
- [ ] 全页截图走查,用户审美确认后合并
- [ ] E2E 全量本地绿

## 2. 详细设计(代码级修改计划)

### 2.1 令牌结构(`web/lib/tokens.ts` 新)

```ts
export const bgVariants = { blue: { bg, paper, divider, outlinedBorder }, green: { ... } }  // 两版候选
export const tokens = { text: 近黑, border: 1px, radius, spacingUnit, elevation(平+细分割线) }
```

- 背景 default 淡色(`#f4f8fb` 系 / `#f2f8f4` 系)、paper 纯白;文字近黑(#1c1b1f 系)。
- 细线:border 1px,浅灰蓝 divider;层次 = **平面 + 分割线 + 卡片描边**,不用重阴影(清爽);sticky 表头/侧栏允许极轻 shadow。
- 紧凑:间距单位收紧(8 → 7)与页面容器 padding 统一,不动行高。
- 定稿机制:实现期 `pick` 读 `process.env.NEXT_PUBLIC_BG_VARIANT ?? "blue"`(仅本地 dev 截图用);**合并前收敛**为常量并删除落选色与 env 读取(临时机制不入库)。

### 2.2 Providers.tsx 重写

消费 tokens:`createTheme({ palette: { background, text }, shape: { borderRadius }, spacing: unit, typography(原样保留), components: { MuiTextField/MuiFormControl → outlined;MuiPaper → elevation 0 + outlined 描边;MuiDivider → 浅色 } })`。结构从 23 行平铺 → 令牌 + 组装两段。

### 2.3 截图脚本(`web/scripts/shots.mjs` 新)

- 复用 playwright + `web/e2e/.env` 凭证与 helpers 逻辑:`apiCreateProject/apiCreateItem/apiCreateSprint/apiAssignMe` 种子数据 → `injectToken(page)` 登录态 → 遍历 walk list `page.screenshot({ fullPage: true })`。
- walk list(17 页):`/`、`/p/{KEY}`(board)、`overview`、`backlog`、`table`、`gantt`、`time`、`/p/{KEY}/sprint/{id}`、`/p/{KEY}/i/{itemId}`、`/attributes`、`/capabilities`、`/members`、`/notifications`、`/search`、`/standup`、`/recycle-bin`、`/login`(登出态)。
- 输出 `.scratch/shots/{variant}/{page}.png`(`.scratch/` 已 gitignore);前置:**8080 后端(test profile)与 dev server 已起**,脚本不代管服务。
- 用法:`BG=blue node scripts/shots.mjs` → `BG=green node scripts/shots.mjs`(env 读法在 dev 编译期生效)。

### 2.4 修改文件清单

| 文件 | 动作 |
|---|---|
| `web/lib/tokens.ts` | 新增(两版候选 + 令牌;定稿提交收敛为单版常量) |
| `web/components/Providers.tsx` | 重写(消费 tokens + outlined 全局) |
| `web/scripts/shots.mjs` | 新增(截图走查工具) |
| `docs/spec/0017-v17-theme-baseline.md` | 本文件,完工补 As-built |

### 2.5 不动清单

- 页面组件本体(零改动——这是「一处改动全局生效」的前提,已核实零显式 variant)
- e2e 用例(grep 确认零 `underline/MuiInput/outlined` 类名依赖)
- 后端、playwright.config、next.config

### 2.6 风险与对策

| 风险 | 对策 |
|---|---|
| outlined 的 notched outline 改变输入框 DOM 细节 → e2e 选择器失效 | 已核实零类名依赖;label 关联机制不变;TC3 全量把关 |
| 紧凑间距过猛压坏表格/对话框 | 不启用全局 density;走查(TC2)把关,间距令牌只调一处 |
| 淡色背景对比度不足(文字可读性) | 文字用近黑(#1c1b1f 级),走查时顺带核;不引入低对比灰字 |
| env 机制忘删、落选色滞留 | 验收清单含「定稿收敛」,As-built 前自查 `grep NEXT_PUBLIC_BG_VARIANT` 归零 |

## 3. 手工测试用例(用户执行)

前置:`cd web && npm run dev`(8080 后端 test profile 在线);`web/e2e/.env` 凭证在位。

- **TC1 两版截图**:`BG=blue node scripts/shots.mjs` → 改 `web/lib/tokens.ts` 的 env 缺省或设 `BG=green` 重跑 → `.scratch/shots/{blue,green}/` 各 ~17 张 → **走查选出背景版**(同时初筛密度/层次)。
- **TC2 定稿走查**:定稿收敛后重截一轮,确认:无下划线输入框、细线层次、间距紧凑不拥挤、文字清晰。
- **TC3 E2E 全量本地绿**:web/ 下标准全量跑法(AGENTS 记载的 E2E_FRONT_PORT=3001 一套),37 例全绿。

## As-built

- **定稿淡蓝**:bg `#f4f8fb` / field `#e9f0f6` / divider `#dde5ec` / border `#c5d3e0` / text `#1c1b1f`,radius 8,spacing 7;候选淡绿 `#f2f8f4` 系走查落选(TC1 两版截图,用户拍板)。
- **方案演进(走查驱动,超出 spec 原文)**:outlined 细线 → **无边框控件**(用户反馈「去掉控件边框,用底色标记」):静止态仅 field 底色,hover 无变化,聚焦 = 底色变纸白 + 1px 边框浮现;MuiPaper 描边平面化 + 按钮 disableElevation。
- 下划线全灭:代码零显式 variant,`MuiTextField`/`MuiFormControl` 默认一处翻转全局生效;e2e 零 underline/MuiInput 类名依赖,实测无 theme 相关红。
- 走查微调(用户手改,item 详情页):Tabs 上方 `mt:3`;评论标题 `mb:1`;评论列表 `mb:0`;评论输入框 `minRows=4`/`maxRows=8`(Enter 发送保留)。
- `scripts/shots.mjs` 落地:17 页 walk list,API 种子数据 + token 注入,输出 `.scratch/shots/`(gitignored);定稿后 env 开关与落选色已删(`NEXT_PUBLIC_BG_VARIANT` grep 归零)。
- TC3:E2E 全量本地 3 轮,首跑 2 错、后两轮全绿(用户拍板接受;非 theme 断言,既有偶发面)。
