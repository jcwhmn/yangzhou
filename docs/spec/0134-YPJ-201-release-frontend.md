# spec 0134 — YPJ-201:Release 管理面板/侧栏/发布动作/挂接(US-R2+R4,前端)

> 状态:设计稿,待用户过目。PRD:Release PRD §6/§7.1/§7.2/§7.4;域拍板:spec 0132/0133。
> 上游依赖:#218(YPJ-191,Release CRUD API)/ #219(YPJ-200,item↔release 挂接 API)已合 main。

## ① 需求

### 范围(In Scope)

1. **Releases 页** `/p/{key}/releases`:
   - 列表:planned 在前(targetDate 升序),released 置灰在后(后端 list 已按此排序);列 = 名称、version、stage、targetDate、状态徽标(released 显 releasedDate)。
   - 新建对话框:名称(必填)、version(可选)、stage(下拉 alpha/beta/rc/ga,可选)、targetDate(可选)。
   - 编辑对话框:同上字段;**released 行仅允许查看,不提供编辑入口**(后端 transitions 已封死,UI 对齐)。
   - 删除:仅 planned 行;released 行无删除按钮(后端摘只删 planned 行)。
   - 发布按钮:仅 planned 行有;点击 → 确认对话框 → `PATCH status=released`(releasedDate 缺省=今天);成功后行移入置灰区。
2. **侧栏 Releases 段**(项目布局侧栏,milestone 段同型):
   - 段头「Releases」可点进页;下方列 **planned 行,最多显 3 条,超出折叠为「+N」按钮,点击展开全部**(再点收起);行 = 名称 · targetDate;无 planned 显示空态提示;released 不进侧栏。
3. **挂接下拉(US-R4)**:item 详情页侧栏,milestone chips 下方新增「Release」区:
   - 显示当前归属(release-membership GET);下拉可选本项目 planned release 或「无」;选择即 `PUT release-membership`;released release 不出现在下拉。
4. **挂接(US-R4,只做 item 侧)**:item 详情页侧栏,milestone chips 下方新增「Release」区:
   - 显示当前归属(release-membership GET);下拉可选本项目 planned release 或「无」;选择即 `PUT release-membership`;released release 不出现在下拉。
   - release 页侧不做 items 列表/添加(后端无该端点,本期不加);也不做挂接计数。
5. **设置门控**:项目设置已有 `releaseEnabled` 开关(YPJ-151 已落);本票把 `flags.release === false` 时隐藏侧栏 Releases 段与 Releases 页入口(与 milestone 门控同型)。设置面板本身不动。

### 非目标(Out of Scope)

- release 页侧的 items 列表/添加、挂接计数(后端无端点,本期不做)。

- Release 详情页(单独路由页)——本期列表行内编辑足够,详情页另票(若 PRD §10.2 要求,待 202/203 之后再议)。
- stage 显示词中文化之外的文案工作(封闭枚举值 alpha/beta/rc/ga 直显,显示词外置 texts.ts 但本期先用原值,中文映射另票)。
- 发布成功率/完成率统计(203)、release create 的 status 通道(202)。
- 服务端权限/门禁拦截(spec 0102 同口径:设置开关仅 UI 门控)。

### 验收

- UI 手测按 ③ 用例逐格走;E2E 补一条 `@smoke` 冒烟(建 release → 侧栏可见 → 发布 → 置灰)。

## ② 详细设计

### 真值表(状态 × 动作 → 行为)

Release 行:

| 状态 \ 动作 | 新建入口 | 编辑 | 删除 | 发布按钮 | 侧栏显示 | 挂接下拉可选中 |
|---|---|---|---|---|---|---|
| planned | ✅(页头按钮) | ✅ | ✅(确认框) | ✅(确认框→PATCH released) | ✅ 名称·targetDate | ✅ |
| released | — | ❌ 无入口 | ❌ 无按钮 | ❌ 无按钮(终态) | ❌ | ❌(下拉中过滤) |

挂接下拉(item 详情):

| 当前归属 \ 选择 | 选某 planned | 选「无」 |
|---|---|---|
| 无归属 | PUT {releaseId} → chip 显示 | 无变化 |
| 已挂 planned A | PUT {B} → 覆盖(弱引用叉表单归属) | PUT {null} → 清除 |
| 已挂 released | 下拉不可达(released 被过滤;历史行只读) | — |

### 页面示意图

Releases 页(`/p/{key}/releases`):

```
┌ ProjectToolbar ──────────────────────────────────────────────┐
├──────────────────────────────────────────────────────────┤
│  Releases                                    [+ 新建 Release] │
│                                                                │
│  ▸ 计划中                                                      │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ v1.0 首个发布   version:1.0  stage:rc  目标:2026-12-01 │   │
│  │        [编辑] [发布] [删除]                              │   │
│  │ v0.9 内测       stage:beta   目标:2026-11-30           │   │
│  └──────────────────────────────────────────────────────┘   │
│  ▸ 已发布(置灰)                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ v0.8        ga   发布于 2026-11-01                     │   │
│  └──────────────────────────────────────────────────────┘   │
│  (空态:「还没有 Release,点右上角新建」)                      │
└────────────────────────────────────────────────────────────┘
```

侧栏(milestone 段之后追加):

```
│ ▸ Sprints           │
│ ▸ Items             │
│ ──────────────────  │
│ Releases            │  ← 段头,点进 /p/{key}/releases
│   v1.0 · 12-01      │  ← planned 行(最多 3 条)
│   v0.9 · 11-30      │
│   v0.8 · 11-20      │
│   +2                │  ← 超出折叠,点开展开全部/收起
│   (空态:无计划中)   │
│ ──────────────────  │
│ Milestones          │
```

item 详情侧栏挂接区:

```
│ Milestones          │
│  [✓] M1 上线        │
│ Release             │
│  [v1.0 首个发布 ▾]  │  ← 下拉:无 / 各 planned release
```

### 代码修改计划

| 文件 | 改动 |
|---|---|
| `web/lib/texts.ts` | 新增 `releases` 文案组(页题/列头/按钮/确认语/空态/侧栏段头/挂接标签,中文) |
| `web/lib/api.ts` | 无需改(通用 api() 够用);Release 类型定义放新组件内 |
| `web/components/ReleasePage.tsx`(新) | 列表+新建/编辑对话框+发布/删除确认;仿 `MilestonePage.tsx` 结构(数据自拉、广播 `releases-changed`) |
| `web/app/p/[key]/releases/page.tsx`(新) | 薄壳,render `ReleasePage` |
| `web/app/p/[key]/layout.tsx` | 侧栏加 Releases 段(`flags.release !== false` 门控):拉 `/api/projects/{key}/releases` 过滤 planned,**显前 3 条 + 「+N」展开/收起**;监听 `releases-changed` 重拉 |
| `web/app/p/[key]/i/[itemId]/page.tsx` | 挂接区:milestones 区下方,GET/PUT `/api/items/{itemId}/release-membership`,下拉只含 planned |

### 否决的备选

- **Release 详情页单独路由**:本期无详情字段超出列表行内容,砍掉。
- **侧栏显示 released 行**:终态是历史,进侧栏只添噪音;页内置灰区已可看。
- **复用 MilestonePage 抽公共组件**:两者字段/动作差异大(发布动作、stage),抽象早了;先平行实现,出现第三个同类页再抽。

## ③ 手工测试用例(用户 build/debug 阶段执行)

前置:prod 或 dev 环境,项目设置 release 开启;后端 8080(dev 用 8081)。

1. **门控**:项目设置关 release → 侧栏无 Releases 段、直接访问 `/p/{key}/releases` 无入口(页仍可达属预期,仅 UI 门控);再开 → 段回归。
2. **新建**:Releases 页 + 新建,只填名称 → 列表出现 planned 行,侧栏同步出现。
3. **新建校验**:名称空提交 → 前端拦截不让提交。
4. **编辑**:改 version/stage/targetDate → 列表与侧栏即时更新。
5. **发布**:对 planned 行点发布 → 确认框 → 行移入置灰区、显「发布于 <今天>」;侧栏该行消失。
6. **终态**:released 行无编辑/删除/发布按钮。
7. **删除**:planned 行删除 → 确认后消失;item 挂接若指向它,后端 0133 已做摘除,UI 刷新后 item 挂接区显「无」。
8. **挂接**:item 详情下拉选 planned release → 显示归属;改选另一个 → 覆盖;选「无」→ 清除;下拉中无 released 项。
9. **空态**:无任何 release 时,页与侧栏显空态文案。
10. **侧栏折叠**:建第 4 个 planned release → 侧栏显 3 条 + 「+1」;点开显全部,再点收起。

## As-built

(完工后补)
