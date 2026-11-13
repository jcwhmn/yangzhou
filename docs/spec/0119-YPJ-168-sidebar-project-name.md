# 0119 — 项目空间侧栏顶部显示项目名(YPJ-168)

> 日期:2026-11-13。票面:项目空间不渲染项目名——layout 拉 `/api/projects/{key}` 只取 release/milestone 开关位丢弃 name;侧栏顶部只有 KEY,用户靠 URL 识别项目(YPJ-163 三层手测时提出)。

## ① 需求

**症状**:进入任意项目空间,侧栏顶部仅显示 KEY(如 `P163A`),项目名(如「163三层手测」)无处可见。

**范围**:
- layout 补存项目 `name`(接口已在调,补 state 即可)
- 侧栏顶部加项目名展示,所有项目页全局生效

**非目标**:全局顶栏改造、全站面包屑、项目改名后侧栏实时刷新(现状 flags 同口径,挂载时拉取)、icon 收纳重组。

**物理约束(设计推导)**:侧栏宽 210px,现状顶部单行 = KEY + 7 个 IconButton,已基本占满。「同行 name+KEY」会把 name 挤到 ~0px,票目标落空 → **在现状行上方加独立 name 行**,现状行(KEY+icons)零改动。

**真值表**(状态 × 渲染):

| 项目 name 状态 | 侧栏顶部渲染 |
|---|---|
| 加载中(null) | 不渲染名行(防闪,与现状全同) |
| 非空串 | KEY 行上方加 name 行(subtitle2 粗,溢出省略) |
| 空串 / 请求失败 | 不渲染名行(= 现状,fail-open 同 flags 口径) |

**页面示意图**(ASCII,贴合实况):

```
改后(210px 侧栏):
┌──────────────────────────┐
│ 163三层手测               │  ← 新增 name 行(粗,溢出…省略)
│ P163A     ★ ⚙ ⊞ ⛭ ⚙ ⚐ ⭳ │  ← 现状行(KEY+7 icons,不动)
│ 概览                      │
│ 流                        │
│ 看板                      │
│ …                         │
└──────────────────────────┘

现状(对照):
┌──────────────────────────┐
│ P163A     ★ ⚙ ⊞ ⛭ ⚙ ⚐ ⭳ │
│ 概览                      │
└──────────────────────────┘
```

## ② 详细设计

`web/app/p/[key]/layout.tsx` 单文件,零后端改动(`ProjectDto.name` 已在响应里):

1. state:`const [projName, setProjName] = useState<string | null>(null)`
2. `loadFlags`:`.then((p) => { setProjName(p.name ?? null); setFlags(...) })`;`.catch` 里 `setProjName(null)`(fail-open 同口径)
3. 顶部结构:现单行 Box 外再包一层(column),名行条件渲染:
   - `projName` 非空 → `<Typography variant="subtitle2" sx={{ fontWeight: 700, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} data-testid="sidebar-project-name">{projName}</Typography>`
   - KEY+icons 行原样下移一层,**零改动**
4. 文案外置:无新增 key(纯变量渲染)

否决备选:
- 同行 name+KEY(物理空间否决,见 ① 推导;若将来要同行需先收 icons,超本票范围)
- 顶栏/面包屑(票面非目标)

## ③ 手工测试用例(用户 build/debug)

1. 进入任意项目 → 侧栏顶部两行:项目名 + KEY(如「163三层手测」/ `P163A`);各页(概览/流/看板/表格/backlog/milestones)切换后名行常驻
2. 长项目名(>13 个汉字)→ 单行内省略号,不换行不破版
3. 断网/接口失败(可暂缓):侧栏回现状 KEY-only,不白屏
4. 回归:收藏星标、工作流/成员/组类型/设置/GitHub/导出七个图标功能不受损

**e2e**:`projects.spec` 建项目用例尾部加断言——侧栏顶部 `[data-testid=sidebar-project-name]` 文本 = 所建项目名(轻量,不新开用例,保单跑性)。
