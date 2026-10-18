# yangzhou web 审美盘点报告(audit)

- 范围:`web/` 全部 17 个路由,桌面 1440×900 + 移动 390×844 双视口,34 张截图存 `audit-screenshots/`(文件名即路由;`_capture.cjs` 为采集脚本,可删)。
- 环境:localhost:3000(dev,代理 PM 实例数据,YPJ 真实内容)。截图左下角深色 "N" 圆钮是 Next dev 指示器,非应用 UI,忽略。
- 方法:逐页截图走查 + 全站源码 grep(39 个 ts/tsx)+ 采样页 computed-style 颜色统计。
- 结论先行:**这是典型的"工具审美"——干净、克制、无 AI 俗套重灾区**(奶油衬线/暗底荧光/报纸分栏/入场动效/大写 eyebrow 全部为 0)。真正的问题不在"俗",而在**三处系统性缺陷**(双导航、移动端、链接样式)和**一处"未选择的默认"**(主色是 MUI 出厂蓝)。

---

## 一、俗套清单逐条判定

| # | 俗套 | 判定 | 证据 |
|---|------|------|------|
| 1 | 奶油底+衬线大标题+陶土强调 | ✅ 无 | bg=#f4f8fb 淡蓝灰(用户拍板),system-ui 无衬线,无陶土色 |
| 2 | 近黑底+单一荧光强调 | ✅ 无 | 全站浅色 |
| 3 | 报纸细线分栏/零圆角/密多栏 | ✅ 无 | 细线用于平面分隔(有意,spec 0017),圆角统一 8 |
| 4 | SaaS 卡片套装 | ⚠️ 部分 | 无阴影无渐变(好);但**全站唯一圆角值 8 无层级**(Chip=卡=输入框=文本域),且页面级"万物皆 outlined 卡"(驾驶舱/Overview/站会/通知/回收站)。属有意系统而非无意俗套,严重度低 |
| 5 | 模板 chrome | ⚠️ 少量 | 全大写 eyebrow/letterSpacing/等宽标签/#0B0B0B 均 0 命中;但**箭头后缀 3 处、中点 meta 5 处、全大写 2 处**,见问题清单 |
| 6 | 标题高亮单词(斜体/变色) | ✅ 无 | 0 命中 |
| 7 | fade-up 入场/每卡 hover | ✅ 无 | Fade/Grow/keyframes 0 命中;hover 仅看板卡/甘特行/Overview 卡,均功能性反馈 |
| 8 | 排版(行宽/字族/type scale) | ⚠️ 部分 | 字族 1 个(system-ui)✅;**行宽无约束**(PageShell 全宽,详情页描述 ~1100px 宽);**无明确 type scale**(tokens 无字号,MUI 默认 + px 字面量两套并存);页标题层级跨页不一致(h4/h5/h6 混用) |

## 二、问题清单

严重度:高 = 视觉缺陷/不可用;中 = 明显失 craft;低 = 细节。

### 缺陷类(非俗套,但走查即见)

| 编号 | 页面 | 问题 | 截图 | 严重度 |
|------|------|------|------|--------|
| D1 | 词表 `/attributes`、站会 `/standup`、回收站 `/recycle-bin` | **双导航栏**:AppNav 已挂 root layout,页面内又渲染一次,顶部出现两条同样的导航。`app/attributes/page.tsx:100`、`app/standup/page.tsx`、`app/recycle-bin/page.tsx` 各自 `<AppNav />`;capabilities/notifications 没有这问题,行为也不一致 | attributes.desktop.png(顶部两条)、standup.desktop.png、recycle-bin.desktop.png | **高** |
| D2 | 全部页面 @390px | **移动端不可用**:全局导航折成竖排单字列("我/的/能/力");项目区固定 210px 侧栏不吃响应式,看板/表格/详情页内容被裁到 ~180px 宽,表格列逐字竖排 | _home.mobile.png、p_YPJ.mobile.png、p_YPJ_table.mobile.png、p_YPJ_i_*.mobile.png | **高**(若产品定位纯桌面工具可降级为"低优先已知限制",需拍板) |
| D3 | Backlog/表格/Sprint/站会/通知/首页 | **默认蓝下划线链接墙**:整列标题全是浏览器/MUI 默认蓝+下划线,信息层级最重的样式给了最不重要的元素;首页"全部 →"更是漏网主题外的**原生蓝 #0000EE**(`app/page.tsx:185`,无色值样式裸 `<a>`) | p_YPJ_table.desktop.png、p_YPJ_sprint_*.desktop.png、search 同款 _home.desktop.png | **中** |
| D4 | 表格 `/p/YPJ/table` | 编号列过窄,"YPJ-28"折成两行竖排;空值列(优先级/开始/截止)一排排"—"无消隐 | p_YPJ_table.desktop.png | 中 |
| D5 | 甘特 `/p/YPJ/gantt` | 时间轴刻度日期不合历法(12-07、09-09、06-12、03-16…),是按跨度均分而非按月取整 | p_YPJ_gantt.desktop.png | 中 |
| D6 | 成员 `/members`、词表 `/attributes` | **红色泛滥**:每行一个红色"删除"文字钮,成员页同屏 40+ 处,红色告警语义被稀释;与红色未读徽章、被阻塞红点挤在一起 | members.desktop.png、attributes.desktop.png | 中 |
| D7 | 工时 `/p/YPJ/time` | "项目合计:296h 24m"用 primary 蓝渲染,视觉上可点击实为静态文本 | p_YPJ_time.desktop.png | 低 |
| D8 | Item 详情 | 描述区作为页面最大内容块,**原始 markdown 源码直出**(`## 背景` 字样);meta 表单行折行凌乱(类型 select 孤行);保存(蓝)与删除(红文字)并排无隔离;评论"发送"是全高 outlined 方块 | p_YPJ_i_*.desktop.png | 中 |
| D9 | Sprint 页 | 空日期渲染成"— ~ —",读作乱码 | p_YPJ_sprint_*.desktop.png | 低 |
| D10 | 首页 | 项目卡内部对齐混乱:KEY chip 与三行折行标题、信号 chip 垂直居中错位,"pool-fix"折成"pool-/fix" | _home.desktop.png | 中 |
| D11 | 站会/导航 | 语言混杂:侧栏 Overview/Backlog/Epic/Milestone/Release 英文,看板/甘特/表格中文;GITHUB tab 全大写;"推进到 IN PROGRESS"按钮状态名大写 | standup.desktop.png、p_YPJ_i_*.desktop.png | 低 |

### 俗套类(对照清单 #4/#5/#8)

| 编号 | 页面 | 俗套 | 证据 | 截图 | 严重度 |
|------|------|------|------|------|--------|
| C1 | Backlog/Sprint/站会/通知 | #5 箭头后缀:"浏览项目 →"(AppNav.tsx:107)、"全部 →"(app/page.tsx:185,且是裸链接蓝)、"V20 Flow 价值闭环 ↗"(item 详情) | 源码 + 截图 | _home.desktop.png、p_YPJ_i_*.desktop.png | 低(本地化语境里像"更多"指示,但确是俗套形) |
| C2 | 通知/Overview/侧栏/Item | #5 中点连接 meta:"GitHub · 2026/10/16 18:16:30"、侧栏 `.join(" · ")`、"To Do · 13"、"me · 5/6" | notifications.desktop.png、p_YPJ_overview.desktop.png | 低(紧凑工具里可辩护;收敛为一种分隔规范即可) |
| C3 | 全站 | #4 圆角无层级:Chip/卡/输入框/文本域一律 8(另有一处 4px 孤值,见一致性表) | 全部截图 | 低 |
| C4 | 首页/Overview/站会/通知/回收站 | #4 万物皆 outlined 卡:三桶/信号/我的/风险全切成同构卡,卡内大量留白失衡 | _home.desktop.png、p_YPJ_overview.desktop.png | 低 |
| C5 | 详情/能力页 | #8 行宽:描述文本域 ~1100px(~95 字符),能力页属性名与选择器相距 1200px | p_YPJ_i_*.desktop.png、capabilities.desktop.png | 中(可读性实伤) |

## 三、全站一致性统计

### 颜色(源码 14 个 hex;运行时采样 4 页)

**应用自有的 6 个中性 token(lib/tokens.ts,单一真相源 ✅):**

| 值 | 用途 | 源码出现 | 备注 |
|----|------|----------|------|
| #f4f8fb | 页面底色 | 1(tokens) | 用户走查拍板,勿动 |
| #e9f0f6 | 控件底(field) | 1 | |
| #ffffff | 卡片纸面 | 1 | |
| #dde5ec | 分割线 | 1 | |
| #c5d3e0 | 描边 | 1 | |
| #1c1b1f | 正文近黑 | 1 | |

**主题路径缺省(MUI 出厂值,未经选择 ⚠️):**

| 值 | 来源 | 运行时频次(采样) | 用在哪 |
|----|------|--------------------|--------|
| **#1976d2** | MUI primary 默认蓝 | ×419 | 链接/主按钮/激活导航/甘特条/进度条/看板 hover 边 |
| #d32f2f | MUI error 默认 | ×66 | 删除钮/徽章/被阻塞 chip |
| #2e7d32 | MUI success 默认 | ×12 | 全满足 |
| #ed6c02 | MUI warning 默认 | 少量 | 有差距(经 Verdict 字面量) |
| rgba(0,0,0,.6) | MUI text.secondary 默认 | ×9724 | 次级文字(最大用量值!) |
| #bdbdbd | MUI grey.400 | ×67 | 禁用 |

**字面量散值(9 个,全在 2 个文件):**

| 值 | 位置 | 问题 |
|----|------|------|
| #2e7d32 | Verdict.tsx(满足) | 与主题 success 同值,**双源** |
| #ed6c02 | Verdict.tsx(候选 delta=1) | 与主题 warning 同值,双源 |
| #c62828 | Verdict.tsx(缺门/RED 信号) | **与 error #d32f2f 不同红**——同为"红"语义两套值 |
| #b45309 | Verdict.tsx(差级) | 暖色第 1 套 |
| #ff9800 | Verdict.tsx(勉强 delta=1) | 暖色第 2 套(与 #ed6c02、#b45309 三琥珀并存) |
| #7c4dff | Verdict.tsx(未评级) | 紫,孤值(非 MUI 任何默认) |
| #9e9e9e ×2 | Verdict.tsx(有余)、p/[key]/page.tsx(指派人兜底色) | 灰,孤值 |
| #f2f8f4 | tokens.ts 注释里(落选候选) | 仅注释,无害 |
| #0000EE | app/page.tsx:185 运行时 | 裸链接,非主题蓝 |

> 运行时另见 #fafbfc ×1749——查证为 Chrome 原生 checkbox 的 UA 底色,非应用值,不算问题。
> 注:Verdict.tsx 注释已自述"字面 hex 而非主题路径"的理由(调用场景需要字符串拼接),属已知取舍,不是事故;但 #c62828 vs #d32f2f 的两个红仍是真不一致。

### 字体

| 维度 | 现状 | 判定 |
|------|------|------|
| 字族 | 全站 1 套:`system-ui, "Segoe UI", "Microsoft YaHei", sans-serif`(Providers.tsx 唯一声明) | ✅ 无第二字族 |
| 字号 | MUI 默认刻度(h4 34 / h5 24 / h6 20 / body1 16 / body2·subtitle2 14 / caption 12)+ **px 字面量第二套**:11×1、12×2、13×3、14×3(集中在 DateInput、members、Verdict) | ⚠️ 两套并存,tokens 无 type scale |
| 页标题 | 驾驶舱=h4、看板/表格/甘特/工时/成员/词表=h5、Overview=h6 | ⚠️ 同级页面三级混用 |
| 字重 | 仅 2 处 600/700 字面量,其余默认 | ✅ 干净 |

### 圆角

| 值 | 来源 | 出现 |
|----|------|------|
| 8 | tokens.radius(theme 全局) | 无处不在 |
| 1(=8,sx 乘数) | 8 处字面量 | 与 token 同值,冗余但无害 |
| 2(=16) | 看板列容器 ×1 | 层级差异,可留 |
| 50 | 圆形 ×1 | 可留 |
| **4(裸 px)** | item 详情 1127 行 raw style | **孤值**,越出 8 基体系 |

### 阴影

应用代码 **0 处 box-shadow**(平面系统,theme 统一 elevation:0);仅看板卡 hover 用 `boxShadow: 6`(MUI 预设档)+ Menu/Dialog 内部默认。✅ 无"同款灰阴影"俗套,无需收敛。

### 间距

`tokens.spacing = 7`(8 基);页面一律 `sx p:3 / mb:2 / spacing={1..3}` 刻度;无越轨大值。⚠️ 唯一不一致:`overview/page.tsx` 自写 `Box p:3` 没用 PageShell(代码组织,非视觉差异)。

## 四、Top 5 优先修复项

按"出现面广 × 明显缺陷/俗套"排序:

1. **拆掉双导航(D1)** — 三个页面删页内 `<AppNav />` 即愈;一行级改动,全站 chrome 立即整齐。
2. **链接规范统一(D3+C1)** — 全站 Link 样式定一条规矩:默认色 text.primary/primary、下划线仅 hover、标题列不当链接墙、删装饰箭头("浏览项目 →"/"全部 →"/"↗"),顺带修 #0000EE 裸链接。影响 6+ 页面,是"模板感"最大来源。
3. **语义色收敛到单一真相源(C 色表)** — Verdict.tsx 字面量改走主题路径或集中成命名 token;两红归一(#c62828→error 或反向);三琥珀并为两档。为下一步 token 方案铺路,顺手把 **primary 是否继续用 MUI 出厂蓝**提上拍板——这是全站唯一的"未选择的选择"。
4. **表格/甘特读感修缮(D4/D5/D9/D7)** — 编号列 white-space:nowrap、空值消隐、甘特刻度按月、工时合计去链接色。全是小改,集中在 4 个文件。
5. **移动端方向拍板(D2)** — 先决策"是否支持移动":支持→导航折叠 + 项目侧栏抽屉化(工作量中等);不支持→加 min-width 提示条,止损即可。**此项需你先表态,非纯实现决策。**

## 五、不建议改的(稳定无害,不值得动)

- **中性 token 六色组**(#f4f8fb/#e9f0f6/#ffffff/#dde5ec/#c5d3e0/#1c1b1f)——spec 0017 用户拍板定稿,走查两轮稳定,收敛度高。
- **平面细线体系**(Paper outlined + 全局去阴影 + disableElevation + 无边框输入框)——成文设计决策,theme 单点维护,自洽。
- **system-ui 字族栈**——中文工具的正确默认,引第三方字体反而增加载与风险。
- **radius 8 单一基** + 16/50 两个功能特例——不是"无层级俗套",是紧凑工具的合理简化;唯一该处理的是那个 4px 孤值。
- **无入场动效/无渐变/无大写 eyebrow/无等宽标签**——已经全数躲开 AI 俗套,不要反向"补课"去加。
- **Verdict 领域色与 DOMAIN.md 绿黄红的绑定**——领域语义,只收敛取值路径,不动语义本身。
- **紧凑密度(spacing 7、caption 12px 元信息)**——工具型产品正确取向。

---

*报告完。等确认优先级后再进入 token 方案设计;截图与采集脚本(`audit-screenshots/_capture.cjs`、根目录 `audit-static.txt`/`audit-cliche.txt` 中间产物)确认无用后可整目录删除。*
