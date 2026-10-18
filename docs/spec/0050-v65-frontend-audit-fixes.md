# 0050 · V65 前端走查修复(audit Top 5,不含移动端)

状态:待过目 → 编码
来源:`audit-report.md`(2026-10-18 前端审美盘点,用户已确认优先级)
决策记录:①主色换掉 MUI 出厂蓝;②移动端本轮不做;③重复语义色收敛到 tokens 单一真相源

## 1. 需求

### 范围(= audit-report 问题清单 D1/D3/D4/D5/D7/D9 + C1 + 色收敛)

1. **双导航删除**:attributes / standup / recycle-bin 三页删除页内 `<AppNav />`(root layout 已持久挂载)。
2. **链接规范统一**(新规矩:链接一律主题 primary 色、下划线仅 hover、无装饰箭头后缀):
   - `AppNav.tsx` "浏览项目 →" → "浏览项目"
   - `app/page.tsx` "全部 →" → "全部",并补主题色(现为浏览器裸蓝 #0000EE)
   - item 详情 "V20 Flow 价值闭环 ↗" → 去箭头
3. **语义色收敛到 tokens 单一真相源**:
   - tokens.ts 新增 `primary` + 语义四色(success/warning/error + 判定域色 gap/neutral),Providers palette 接线
   - Verdict.tsx 字面量改引 tokens;**两红归一**(缺门/RED = error)、**三琥珀归两档**(勉强 delta=1 由 #ff9800 并入 #ed6c02)
   - 主色由 MUI 出厂蓝 #1976d2 换为 **#2E6FAF**(钢蓝,与 #f4f8fb 底色同族、饱和度降一档;候选备选 #1D4ED8/保留出厂蓝,过目时可换)
4. **表格读感**:编号列 nowrap(表格/Sprint/Backlog 三处);表格页空值列"—"消隐(空白替代)。
5. **甘特刻度按月取整**(现为跨度均分,出现 12-07/09-09 等非历法日期)。
6. **工时页合计**去链接色(primary → text.primary + 600)。
7. 顺带:item 详情页 1127 行裸 `borderRadius: 4` → 8(圆角孤值归一)。

### 非目标(本轮不做)

- 移动端适配(D2,已拍板搁置)
- 描述 markdown 渲染、红色泛滥重设计、type scale/页标题层级(D8/D6/D11 留票)
- Overview/站会等"万物皆卡"布局重排(C4)

## 2. 详细设计(文件级)

| 文件 | 改动 |
|---|---|
| `web/lib/tokens.ts` | 新增:`primary: "#2E6FAF"`、`success: "#2e7d32"`、`warning: "#ed6c02"`、`error: "#c62828"`、`gap: "#b45309"`、`neutral: "#9e9e9e"`、`accent: "#7c4dff"`(判定域色,注释标明 DOMAIN.md 语义)。既有六中性值不动 |
| `web/components/Providers.tsx` | palette.primary/success/warning/error 从 tokens 接线 |
| `web/components/Verdict.tsx` | colors 表改为引 tokens(仍导出字符串值,保住 borderLeft 拼接场景);`candidateColor` 的 #ff9800 → tokens.warning;RED/satisfied → tokens.error/success |
| `web/components/AppNav.tsx` | 删"浏览项目 →"箭头 |
| `web/app/page.tsx` | "全部 →"去箭头;Typography component=Link 补 `sx={{ color: "primary.main" }}`(消 #0000EE) |
| `web/app/p/[key]/i/[itemId]/page.tsx` | 删 sprint 名后 "↗";1127 行 borderRadius 4→8 |
| `web/app/attributes/page.tsx`、`app/standup/page.tsx`、`app/recycle-bin/page.tsx` | 删页内 `<AppNav />` + 对应 import |
| `web/app/p/[key]/table/page.tsx` | 编号列 TableCell `sx={{ whiteSpace: "nowrap" }}`;空值单元格渲染空串而非 "—" |
| `web/app/p/[key]/sprint/[id]/page.tsx`、`app/p/[key]/backlog/page.tsx` | 编号列 nowrap |
| `web/app/p/[key]/gantt/page.tsx` | 刻度生成改为按月边界(从 range 起点月起,每月 1 号一 tick,超出右界止) |
| `web/app/p/[key]/time/page.tsx` | "项目合计"h6 `color="primary"` → `sx={{ color: "text.primary", fontWeight: 600 }}` |

否决的备选:统一抽 `LinkButton` 组件包装链接规范——现仅 ~6 处,MUI `Link` + 两个 sx 即可,抽象不划算;全局 CSS `a { underline hover }`(MUI CssBaseline 侧)——影响面不可控,先点杀。

## 3. 手工测试用例(编码后你执行)

1. 词表/站会/回收站:顶部**只有一条**导航;active 高亮仍随路由变。
2. 首页:"全部"为钢蓝色、无下划线、hover 才出下划线;点击仍去通知页。
3. 左侧收藏菜单:"浏览项目"无箭头。
4. item 详情:sprint 链接无 "↗";整体主色(按钮/激活导航/甘特条/进度条)全部变为钢蓝 #2E6FAF,无残留出厂蓝。
5. 判定区:满足=绿、缺门=红(#c62828)、差级=琥珀、勉强=橙(#ed6c02,与"有差距"chip 同色)、未评级=紫、有余=灰——语义不变仅取值收敛。
6. 表格页:编号列单行;优先级/开始/截止空值显示为空白(非"—");看板/甘特/工时主色同步变。
7. 甘特:刻度为每月 1 号(如 11-01、12-01…),不再出现 12-07 类日期。
8. 工时页:"项目合计"为深灰粗体、非蓝色。
9. 移动端不回归验证(已声明搁置)。

## 4. 验证口径

`cd web && npx next build` 绿 + Playwright 冒烟(`--grep "@smoke"`)绿;涉及色断言的 e2e 若有(检查后确认)同步更新。

## 5. As-built(2026-10-18,分支 feat/YPJ-65-frontend-audit-fixes)

- 落地与设计一致,无偏离。commits:`61f8c98`(功能 15 文件)+ `6d0c2e8`(e2e 断言随行)。
- e2e 断言随行为更新两处(均为预期影响,非回归):
  - `item-assign.spec.ts` 勉强(delta=1)色 `rgb(255,152,0)` → `rgb(237,108,2)`(三琥珀归两档)
  - `sprint.spec.ts` 回链 `"Sprint 43 ↗"` → `"Sprint 43"`(C1 去箭头)
- 验证:`next build` 绿;item-assign 11/11、sprint.spec 7/7 绿;用户全量 e2e 其余用例绿。
- 甘特刻度实现:起点次月 1 号起逐月一 tick,UTC 口径(`Date.UTC`/`toISOString`),与既有 `xOf` 百分比定位兼容。
- 遗留:push/PR 因 github.com 连接失败暂挂(本地分支领先 2 commits),网络恢复后 push → gh pr create → `yz items move YPJ-65 "In Review"`。
