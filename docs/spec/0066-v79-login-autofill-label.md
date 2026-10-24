# 0066 — YPJ-79 登录页自动填充 label 虚影

> 票:YPJ-79(XS)· 影响:`web/app/login/page.tsx` 一处

## 需求

浏览器/密码管理器自动填充不经 React 状态,MUI label 保持居中不收缩,与填充的用户名叠成虚影。

## 设计

登录页 username/password 两个 TextField 加 `InputLabelProps={{ shrink: true }}`(label 恒浮起)。否决:用 useEffect 轮询检测 autofill(复杂度不值)。

## 手测

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | 打开登录页(有保存凭证的浏览器) | 无虚影,label 恒浮于输入框上方 |


## As-built(2026-10-24,PR #137 合入)

- 按设计落地,一行 × 2(shrink);用户手测虚影消失。
