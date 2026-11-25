# workflow — 票务与交付流

> 由 AGENTS.md §真相源 触发加载:开票/推进状态/指派/排期/分支/PR/合并/sprint 收口前必读。

## Issue 工作流(PR 模式;票在 YPJ 项目,2026-10-06 起 Linear 冻结只读)

1. 开工:`git checkout main && git pull --ff-only`(分支经 yz 创建,见下);同时 `yz assign YPJ-{N} me && yz items move YPJ-{N} "In Progress"`(开工须有主,未 assign 会被 move 409 拒);开工票**必须挂当前 sprint**(`yz items assign YPJ-{N} <当前sprint>`;立票可不挂留 backlog,但开工动作必须含挂 sprint——不允许悬空票:sprint 过程零痕迹=回顾漏计,YPJ-174 裸闭案例,2026-11-13 用户定,废除 10-24「不自动挂」口径);**分支经 yz 创建并关联 item**(2026-10-12 定,YPJ-27 起 CLI 直建:`yz items branch YPJ-{N} <type>/YPJ-{N}[-依赖票号][-英文slug] --base main`,命名=spec 0036 v2(`cwjiang/` 前缀规则已废弃,2026-10-16 用户纠正);`yz log` 依赖该关联;本地对应 `git fetch && git switch` 跟踪远端新分支)
2. 实现带测试,跑全相关验证
3. PR:`gh pr create --base main --title "YPJ-{N}: ..."`;开出后 `yz items move YPJ-N "In Review"`
4. **停**,等用户审阅合并
5. 合并后同回合完成:`git checkout main && git pull --ff-only` → 删远程分支(`git push origin --delete <branch>`;**本地分支永久保留不删**,2026-11-01 定,过程考古走 `git log <本地分支>`;`gh pr merge --delete-branch` 连本地一起删,**禁用**;GitHub 侧 refs/pull/N/head 仍可回溯,YPJ-69)→ **YPJ 移 Done**(`yz items move YPJ-{N} "Done"`,非可选)→ 扫父 item:子票全 Done 则父票 Done,否则 In Progress。PR 已合而 item 停 In Progress = 流程破损态。

## 票卡排期纪律(2026-11-17 定,用户拍板)

① 每个新 item 建票必带预估时长(estimateValue+estimateUnit);② item 进 sprint(backlog 拉入或建票直挂)必设预计开始时间,结束时间 = 开始 + 预估工作时长换算(DATE 粒度:不足一天=当天,跨天进一);③ 这是 dogfood 流程纪律兼未来系统强约束的先行版(甘特计划线需要,见 `docs/requirement/甘特图 SRS.md`);④ CLI 已支撑(2026-11-25 核实):建票 `--estimate 4h/2d/1w`(YPJ-186)、`items schedule`(YPJ-208/0138,due 缺省按 start+estimate 铺算);原缺口票 YPJ-207 已关闭(superseded)。

## 复杂票拆解(2026-11-22 用户定)

复杂票(多端/多面、预估超一个会话)**用子 item 拆**:父票下建子 item(每子票带预估时长、挂当前 sprint),一子票一分支一 PR,各自走完 In Progress→In Review→Done;父票随子票全 Done 而 Done。**不做同票多切片连续 PR**(2026-11-22 YPJ-226 特例获用户同意,仅限该票,不复用)。

## sprint 收口

sprint 全部票 Done 时,主动提醒用户做 sprint 收口/handoff(由用户执行);handoff 换届将 `.scratch/handoff/CURRENT.md` 挪 `.scratch/handoff/archive/YYYY-MM-DD-YPJ-N.md`,不覆盖。

## 过程档案(2026-11-01 立,YPJ-131 复盘)

每个验证绿点就地 commit(消息带票号+绿点说明),不攒「一票一提交」;PR squash 不变——main 干线干净、本地分支留全程细粒度历史。

## 工具速记

- `cmd //c yz …` 直跑;`head -N` 会截断 yz 列表输出,查多票用 `yz items show` 逐票
- 票务 API 直调模板、session 位置、坑(编号非主键/checklist 单数/409)→ skill `C:\Users\Administrator\.pi\agent\skills\yangzhou\SKILL.md`
