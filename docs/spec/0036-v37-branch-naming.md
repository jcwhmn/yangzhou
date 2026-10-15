# 0036 — YPJ-37 web 建分支预填:type/票号 命名规则(评审定案版)

> 伞设计:spec 0033 §五。票:YPJ-37。分支 `cwjiang/YPJ-37-branch-naming`。
> v2:用户定案命名规则(2026-10-15),替代第一版「登录名/票号」方案。

## ① 需求

**预填命名规则**(用户定案,可改):

```text
{type}/{票号}[,{依赖票号}…][-{英文slug}]
```

1. 前缀 = item type 直接用(`task`/`bug`/`goal`/`story`;DB CHECK 与 service 校验同域)。type→fix 映射不做,取「直接用 type」读法——**请人复核**。
2. 其后为票号:`task/YPJ-37`。
3. 关联多个 item(依赖关系,`GET /api/items/{id}/dependencies`)→ `,` 分隔:`task/YPJ-37,YPJ-38`(git ref 允许逗号)。
4. title 含中文(非纯 ASCII)→ 到此结束。
5. title 纯英文/ASCII → 追加 slug:`task/YPJ-37-automate-github-branch-name`。

**禁止 hardcode 任何用户名**(前版 `cwjiang/`、登录名前缀方案均废弃;`yz-username` 机制一并回退,不再需要)。

**非目标**:后端零改动(`requireValidBranch` 既有校验兼容新名字:字母数字`._-/`+逗号?——**逗号不在既有字符集内,见 ②.4**);CLI 不动。

## ② 详细设计

纯前端,`web/app/p/[key]/i/[itemId]/page.tsx`:

1. 回传 `item.type` / `item.title`(此前删掉的 `itemTitle` prop 以 `item={item}` 整体或两字段回传,查 Item 客户端类型)。
2. 预填 useEffect:
   - 依赖:`api(`/api/items/${itemId}/dependencies`)` 取 `dependencies[].number`(失败/空 → 只有主票号);
   - slug:`title` 仅含 `[A-Za-z0-9 -]`(含空格)→ 小写、非 `[a-z0-9]` 转 `-`、去首尾 `-`;否则无 slug;
   - 拼接:`${type}/${[主票号,...依赖票号].join(",")}` + slug ? `-${slug}` : ``。
3. 回退第一版引入的 `yz-username`:`web/lib/api.ts` 的 `getUsername`/登出清理、`login/page.tsx` 的写入,全部删除。
4. **后端字符集**:既有 `requireValidBranch` 正则 `^[A-Za-z0-9._\-/]{1,200}$` **不含逗号**——规则 3 的 `YPJ-37,YPJ-38` 会被 400 拒。→ 字符集加 `,`(机械合法性,非语义修正,符合伞定案),同步补单测一条(字符集矩阵在 unit 侧;若无既有单测则集成侧 GithubBranch 相关用例补)。

**否决的备选**:type→fix 映射表(用户给的两可,取直接用,少一张表);登录名前缀(见 ①)。

**测试**:预填逻辑纯前端无单测;后端字符集加宽必须带测试;E2E 全量由用户 merge 前跑。

## ③ 手工测试用例(供 build/debug)

1. task、中文标题、无依赖:预填 `task/YPJ-37`。
2. bug、英文标题 `automate github branch name`:预填 `bug/YPJ-37-automate-github-branch-name`。
3. 有依赖 YPJ-38:预填 `task/YPJ-37,YPJ-38`。
4. 混排 title(含中文)→ 无 slug 段。
5. 用预填名实际建分支成功;重名仍报「分支已存在」。
## As-built(2026-10-15,PR #106 合入,spec 已随定案更新为 v2)

- 最终规则为用户三轮定案(v2):预填 `{type}/{票号}[,依赖票号…][-英文slug]`——type 直接采用(含中文等非 ASCII 不产 slug);依赖票号取 `/api/items/{id}/dependencies` 后缀拼接;英文标题自动出 slug 段,可编辑。
- 后端:`GithubBranchService.requireValidBranch` 字符集加 `,`(依赖票号分隔);其余校验不变。
- **整体回退**:v1 引入的 `yz-username` 机制(登录名做前缀)实测不符合 GitHub 惯例(8081 登录名 `me`),相关代码全部移除,不留配置残留。
- 验证:预填→建分支→`yz log` 关联提交全链路手工通过(借 YPJ-53 做验证素材票);E2E/既有测试不受影响。
- 偏差:相对 v1 的两轮返工均发生在设计评审期,未产生已合入的废代码;最终形态与 spec v2 一致。

