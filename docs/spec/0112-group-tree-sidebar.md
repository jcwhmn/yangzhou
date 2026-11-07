# 0112 — ItemGroup 两层树·Web 侧栏(YPJ-112)

父票 YPJ-110;依赖 0111(后端 parent 已合入)。术语零 hardcode:level-2=父层、level-1=叶层,sprint/epic 只是类型名。

## 一、需求

### 1.1 范围

- 项目侧边栏 Sprint 段:存在 level-2 类型时改渲染**两级树**——
  - active 父层组默认**展开**(− 前缀),展示其子组(active→planned→completed 排序同现状);
  - 非 active 父层组**折叠**(+ 前缀);点 + 展开该组子列表(再点收起);
  - **空父层组(无子组)不显示 +/− 前缀**(用户 2026-11-11 补充拍板);
  - 无父叶层组保持现状平面列表(树下方,向后兼容);
  - 无 level-2 类型 → 现状平面列表,零变化(存量项目/现有 e2e 不受影响)。
- **新建叶层组默认落「当前 epic」**:关闭最后一个 active 子组后再新建,仍挂当前 epic。当前 epic 判定:优先 active 父层组;无 active 则用 localStorage 记忆(`yz-current-parent:{projectKey}`,观察/创建时更新);都没有 → 无父(向后兼容)。新建对话框带父层选择器(可改)。
- 子组动作(sprint 开/关)走 0111 联动,前端无新增逻辑(广播 sprints-changed 已有,重拉后父层状态即时反映)。

### 1.2 非目标

- 父层组详情页(行点击=展开/收起,不导航);多层/异形;metrics rollup;固定两层之外层级渲染。

## 二、详细设计

### 2.1 `web/lib/sprints.ts`

- `GroupDto` 补 `parentGroupId: string | null`;`toSprint` 带出(`Sprint.parentGroupId`)。
- 新增 `listParents(key): Promise<Parent[]>`:`GET /groups?level=2`(Parent = Sprint + children?不嵌套,前端组树)。
- 新增 `listGroupsAll(key)`:全量组(一次请求,前端组树,避免两次往返)。
- `createSprint` body 增 `parentGroupId?: string | null`。

### 2.2 `web/app/p/[key]/layout.tsx`(侧栏)

- 数据:`loadSprints` 改为:取 group-types;有 level-2 类型 → `listGroupsAll` 一次拿全量,前端分桶 `parents`(level-2)/`children`(挂父叶层)/`orphans`(无父叶层);无 level-2 → 现状 `listSprints`。
- 状态:`expanded: Set<string>`(初始=active 父层组;用户手动开合叠加);`currentParentId: string | null`(优先 active 父层组,否则 localStorage 读,写回时机=观察到非空父层集时)。
- 渲染树(父层组按 active→planned→completed 排序):
  - 行:`{parent.children.length > 0 ? (expanded ? "−" : "+") : ""} {name}`,前缀为 IconButton(size=small),点击 toggle expanded;行本身也 toggle(无详情页,无导航冲突)。
  - 展开时子组渲染在下方,缩进(`pl: 3`),复用现有 ListItemButton→sprint 详情页链接与 selected 逻辑。
- 新建对话框:加父层下拉(`MenuItem`:无父 + 各父层组);默认值 `currentParentId`;创建带 `parentGroupId`(空=不传)。
- localStorage 读写包 try/catch(SSR 安全,`typeof window` 守卫)。

### 2.3 否决的备选

- 后端组树端点(`/groups/tree`):前端一次全量+客户端组树已够(组量小),不加端点。
- 展开态放 URL/全局 store:侧栏局部交互,组件态足够。
- 当前 epic 记忆放后端(项目属性):为 UX 细节动 schema 不值,localStorage + active 优先已覆盖用户场景。

## 三、界面规格(§一点五,照 0110 粒度)

### 3.1 交互序列

```text
载入 → 有 level-2 类型?
  ├─ 否 → 平面列表(现状,不变)
  └─ 是 → 全量组 → 分桶 → 树:
        active 父层 → 展开(−)+ 子组列表
        其余父层   → 折叠(+)
        空父层     → 无前缀
        孤儿叶层   → 树下平面列表(现状样式)
点击 +/−/行 → 该父层展开/收起(互不干扰,各自 Set 成员)
点子组行 → 导航 sprint 详情页(现状)
段头 + → 新建对话框(父层选择器默认「当前 epic」)
```

### 3.2 ASCII 线框(侧栏 Sprint 段)

```text
Sprint 段头                 [+]
───────────────────────────────
− Epic 26-3        (active)
    Sprint 3-1  进行中
    Sprint 3-2  进行中
    Sprint 3-3  计划中
+ Epic 26-2        (已完成)
+ Epic 26-4        (计划中)
Sprint 孤儿-1       (无父叶层,平面)
```

空父层:

```text
  Epic 空的            ← 无前缀,点击无展开效果
```

### 3.3 状态 × 动作 → 行为(真值表)

| 父层状态 | 子组数 | 前缀 | 点行/前缀 | 新建默认父 |
|---|---|---|---|---|
| active | >0 | − | 展开↔收起 | 是(若唯一 active) |
| active | 0 | 无 | 无效果 | 是 |
| planned/completed(FINAL 语义非 FINAL 态名) | >0 | + | 展开↔收起 | 否 |
| planned/completed | 0 | 无 | 无效果 | 否 |
| (无 level-2 类型) | — | — | 平面列表 | 无选择器 |

「当前 epic」回退链:active 父层 → localStorage `yz-current-parent:{key}` → 无父。

## 四、手工测试用例

- T112A(dev,leader):建 Epic 类型+组 E1(空)→ 侧栏 E1 无前缀;建 S1 挂 E1 → E1 出现 +;E1 start?? — 父层禁手动,E1 保持 planned;start S1 → E1 自动 active 且展开(−),S1 在列。
- T112B:E1 下 S1 start→complete(唯一子)→ E1 自动 completed,侧栏收起为 +;不动页面直接点段头 + 新建 S2 → 父层选择器默认 E1(localStorage)→ 创建后 S2 在 E1 下,E1 仍 completed。
- T112C:两父层 E1/E2,E1 active 展开、E2 折叠;点 E2 行展开,点 E2 前缀收起;互不影响。
- T112D:老项目(无 Epic 类型)→ 侧栏与升级前完全一致(平面)。
- T112E:孤儿 sprint(无父)在树下平面区,可正常导航。

## 五、验收(YPJ-112)

- e2e:新增树形态用例(建 epic 类型/组 → 断言展开/折叠/空父无前缀/新建默认当前 epic);现有 e2e 全绿(无 level-2 项目不受影响);prettier 过;`npx playwright test` 本地全量。
## 六、增量:两层项目新建叶层组必挂父(2026-11-11 用户拍板)

- **不变量**:项目存在 level-2 类型 ⇒ 新建 level-1 组必须带 parentGroupId;存量悬空 grandfather 不迁移、照常展示操作(树上平面区仅存量)。
- **后端**:`createGroup` 校验(400 引导文案「请先建父层组」);0 个 epic 实例同样拒绝。组改父能力另票 **YPJ-160**(epic 保持哑容器)。
- **前端**:对话框去「无」选项;当前 epic 兜底链 = active → localStorage → 第一个父层组(`syncCurrentParent` 统一判定,侧栏/处置流/看板三入口单源);处置流新建组继承被完成 sprint 的父(悬空 bug 修复,实锤:处置「新建组并激活」未带 parent)。
- **集成测试**:`GroupParentApiTest` 8 例(新增两级项目无父 400 / 纯 level-1 无父照旧 201)。
- **验证**:build + integrationTest 双绿;e2e 全量绿(flake 桶复跑单跑绿)。

## 七、As-built(2026-11-11,分支 feat/YPJ-112-group-tree-sidebar)

- `web/lib/sprints.ts`:GroupTree/listGroupTree/syncCurrentParent/createSprint(parentGroupId)。
- `web/app/p/[key]/layout.tsx`:两级树渲染(+前缀/− 展开/空父层无前缀)、开合互不影响、新建对话框父层选择器。
- 悬空修复:`sprint/[id]/page.tsx`(处置 S3 继承被完成组父)、`page.tsx`(看板 S3 落当前 epic)。
- 差异:spec 2.2 原写「前缀为 IconButton」,实现为文本前缀(整行点击开合,更简,交互语义一致)。
