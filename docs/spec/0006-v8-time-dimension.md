# Spec — yangzhou V8:时间维度与汇报

> 来源:V8 grilling(Round 1,2026-09-13 全票);决策记录见 Obsidian CONTEXT.md「V8 已定」。词汇表/ADR 同 V1–V7。

## Problem Statement

V6/V7 让工作自动流进来(GitHub → 状态 → 通知),但 item 没有日期、没有工时、没有汇报出口——**管得了进展,管不了时间**。排期、报工、向老板汇报都缺一环。

## Solution(四块)

1. **日期**:item 加 `startDate`/`dueDate`(均可空,start≤due);详情编辑、看板卡片展示、超期红色标识。
2. **甘特**:单项目树形甘特视图(父子可折叠,只读)。条 = startDate→dueDate;缺 startDate 画里程碑菱形;无日期 item 归「未排期」折叠组;行尾状态 chip;点条跳详情。
3. **工时**:`TimeEntry`(计时器 + 手动补录)。item 详情工时区块 + 合计;项目工时页按人聚合简单报表。
4. **Excel**:Apache POI 出真 xlsx(sheet1 item 清单按树序,sheet2 工时明细),后端流式下载。

## User Stories

1. As a 管理者, I want item 有起止日期并在看板/详情可见, so that 排期有处落。
2. As a 管理者, I want 甘特图总览项目排期与超期, so that 一眼看进度。
3. As a 成员, I want 一键开始/停止计时,也可手动补录, so that 工时记录零负担。
4. As a 管理者, I want 看到按人聚合的工时报表, so that 汇报有数。
5. As a 管理者, I want 一键导出 xlsx, so that 老板要什么给什么。

## Implementation Decisions

- **V16 migration**:`item.start_date date`、`item.due_date date`(均可空,CHECK start≤due;存量 null);父子日期独立手填,**不做自动聚合**(真痛再 rollup)
- **超期标识**:`dueDate < today && 非终态` → 看板/甘特红色;**到期提醒通知 defer**(V7 管道现成,后补一条规则即可)
- **甘特**:单项目;后端 `GET /api/projects/{key}/gantt`(树序 + 日期 + 状态);前端渲染(自绘 div 条,不引图表库);只读,**拖拽改日期 defer**
- **V17 migration + 工时**:`time_entry`(item FK cascade / member FK / started_at / ended_at 可空 / minutes 可空 / note);**计时器全局唯一**——开新自动停旧(ended_at=新起点);手动补录可只给 minutes;任意项目成员可记(assignee 默认选中)
- **工时 API**:`POST /api/items/{id}/time-entries`(计时开始或补录)/ `POST /api/time-entries/{id}/stop` / `GET /api/items/{id}/time-entries`(明细+合计)/ `GET /api/projects/{key}/time-summary`(按人聚合)
- **Excel**:`GET /api/projects/{key}/export.xlsx`(POI,流式;新增依赖 `org.apache.poi:poi-ooxml`)
- **测试**:黑盒照旧;日期校验/超期标识/甘特排序/计时器唯一自动停旧/手动补录/合计与聚合/xlsx 下载头

## Testing Decisions

Testcontainers 黑盒:日期 start>due 400;甘特数据按树序含未排期组;开新计时器自动停旧;手动 minutes 与起止二选一;time-summary 按人聚合正确;xlsx 响应 Content-Type 与非空体。

## Out of Scope

到期提醒通知(后补规则)· 拖拽改日期 · 跨项目甘特 · 日历视图 · 自动 rollup · Excel 导入 · CLI 工时命令。

## Further Notes

- 切票:S1 日期(V16)→ S2 甘特 → S3 工时后端(V17)→ S4 工时 UI/报表 → S5 Excel → S6 E2E 扩展收尾。
- 另有配套小票:**yangzhou-manager agent skill**(CLI 包装,任意项目会话经 `yz` CLI 记 item/推进,YPJ 直接吃自己狗粮;不占 V8 主体)。
