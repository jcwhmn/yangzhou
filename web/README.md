# yangzhou web

Next.js + MUI + TypeScript,OpenAPI 契约的瘦客户端(API-first,无业务逻辑)。中文界面,文案外置(`lib/texts.ts`)。

## 运行

```bash
# 前置:API 在跑(默认 http://localhost:8080,可用 NEXT_PUBLIC_API_URL 覆盖)
npm install
npm run dev        # http://localhost:3000
```

## 手工 smoke 清单(每版本跑一遍)

前置:API 已启动;localStorage 无 `yz-token`(或用无痕窗口)。

- [ ] 访问任意页未登录 → 自动跳 `/login`;登录(全新服务器自动 bootstrap)→ 落到项目列表
- [ ] 错误密码 → 页面红字显示笼统错误
- [ ] 项目列表:输入 KEY(自动大写)+ 名称 → 创建,卡片出现(KEY chip + 名称 + 可行性信号 chip)
- [ ] 重复 KEY → 红字 409
- [ ] 点项目卡 → 看板:三列(To Do / In Progress / Done),列头带计数
- [ ] 顶部输入框新建 item → 出现在 To Do 列,带编号 chip(CHE-1)与信号 chip
- [ ] **拖卡片**到 In Progress → 列表移动(乐观更新);拖到 Done 同理;非法迁移(配置过 transitions 时)→ 回滚 + 红字
- [ ] 点卡片标题 → item 详情:编号 chip + 聚合信号 + 状态下拉
- [ ] 状态下拉切换 → 保存后详情状态变化,返回看板位置已变
- [ ] 改标题/描述 → 保存 → "已保存"闪现
- [ ] **需求编辑**:加一条(属性下拉 + ≥等级/不分级)→ 保存 → **判定行与聚合信号即时刷新**:
  - ✓ 满足(绿)/ ✓ 有余(灰,克制不展开)/ △ 差 N 级(琥珀,含需/有)/ △ 有但未评级(紫,差距未知)/ ✗ 缺能力(红)
  - 底部"缺门 N · 总差距 M 级"
- [ ] 删需求条目 → 保存 → 判定行消失
- [ ] 中文与 ✓△✗ 无乱码

## 自助管理页(JCW-83)

前置:已登录,词表/能力/项目有数据。

- [ ] 顶部导航(项目/我的能力/词表)三页互跳
- [ ] **能力页**:每个词表属性一行;设 Lv/未评级/无,改动即存("已保存"闪现);非分级属性只有 无/有
- [ ] 能力改动后回项目列表/详情:可行性信号与判定行立即反映新自评
- [ ] **词表页**:新增属性(skill 分级默认);kind 切换(skill↔label);取消分级/分级
- [ ] leveled 关→开:能力页该属性恢复可选等级(数据休眠未删)
- [ ] 删除被能力/需求引用的属性 → 红字 409
- [ ] **短板面板**(首页):按属性聚合缺口(缺门数/共差级数/未评级数 + 涉及 item 编号链接);无缺口显示"暂无缺口 ✔";点编号跳对应 item 详情
- [ ] 全部中文界面,文案在 lib/texts.ts

## 结构

```
lib/api.ts            瘦客户端(token/401 跳转/bootstrap-or-login)
lib/texts.ts          文案(中文)
components/Verdict    判定行 + 信号 chip(颜色语义同 DOMAIN.md)
app/login             登录
app/                  项目列表(+每项目可行性信号+短板面板)
app/capabilities/     能力自评
app/attributes/       词表管理
app/p/[key]/          看板(列=Status,HTML5 原生拖拽,乐观更新)
app/p/[key]/i/[id]/   item 详情(编辑 + 需求 + 即时判定)
```
