# 0036 — YPJ-37 web 建分支预填:极简安全默认(移除 slug 拼接)

> 伞设计:spec 0033 §五。票:YPJ-37。分支 `cwjiang/YPJ-37-branch-naming`。

## ① 需求

**背景**(dogfood 实证):web 建分支预填 `<KEY>-<序号>-<标题slug>`——slug 把非 `[a-z0-9]` 全剥成 `-`,CJK 标题产出空/残迹 slug(`YPJ-41-`),ASCII 长标题产出超长碎片,且缺 `cwjiang/` 前缀,每次手改。CLI `yz items branch` 已是命名精确的正路径(YANGZOU 实践惯例 `cwjiang/YPJ-{N}-{short}`)。

**范围**:web 建分支预填退化为 **`{登录名}/{itemNumber}`**(如 `jcwhmn/YPJ-42`),删除 slug 拼接逻辑;登录名不可得时退化为裸 `{itemNumber}`。**禁止 hardcode 任何用户名**(评审定案:多人语义必须成立)。用户仍可编辑。

**非目标**:
- 后端不动:`requireValidBranch` 已校验字符集/长度/`..`/`.lock`,只管合法性不做语义修正(伞定案);无 schema/API 变更。
- CLI 不动(已是正路径);`yz` 文档措辞无实际错误,不改。
- 不在 web 重建 slug 智能命名(中文 slug 化属投机复杂度;需要精确名时用 CLI)。

**验收**:任意标题预填名 = `{登录名}/YPJ-{N}`(无登录名则裸票号),无语义残迹/超长碎片;不 hardcode 用户名,多人语义成立。

## ② 详细设计

纯前端,零契约变更。

1. `web/lib/api.ts`:登录成功路径存 `localStorage("yz-username")`;登出随 `yz-token` 一并清理。
2. `web/app/p/[key]/i/[itemId]/page.tsx` 预填 useEffect:删 slug 拼接与 `keyPart`/`numPart`,改为 `setName(用户名 \`\${用户名}/\${itemNumber}\` : itemNumber)`;注释说明 V19 YPJ-37 决定(slug 残迹)与 CLI 正路径。
3. `itemTitle` prop 不再使用,一并清理。
4. 已登录旧会话无 `yz-username` → 退化裸票号(重新登录后恢复完整前缀),可接受。

**否决的备选**:ASCII 标题保留 slug、CJK 退化——两条路径两个行为,增加认知与测试面,无实证需求;统一极简默认。

**测试**:纯前端预填,无单测;E2E 现有用例(branches 相关如有)断言的是创建行为非预填名,预计不受影响(跑冒烟确认)。

## ③ 手工测试用例(供 build/debug)

1. PM 实例(8081):任一 item(中文标题)详情 → 建分支对话框:预填名 = `cwjiang/YPJ-{N}`,可编辑。
2. 用预填名实际建分支:成功,GitHub 上分支名正确;详情页 GitHub 分支卡片显示该分支。
3. 重复建同名分支:仍报「分支已存在」(后端校验不变)。
