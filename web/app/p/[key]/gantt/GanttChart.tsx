"use client";

import { Box, Button, Chip, Tooltip, Typography } from "@mui/material";
import Link from "next/link";
import { type RefObject } from "react";
import { DAY, type GanttRow, type HeaderRow } from "./types";
import { parseLocal } from "@/lib/dates";
import {
  dueFromJs,
  estimateHoursOf,
  offsetBadge,
} from "./utils/timeCalculations";
import { HoverCard } from "./components/HoverCard";
import { TimelineHeader } from "./components/TimelineHeader";
import { TodayLine } from "./components/TodayLine";
import { SummaryBar } from "./components/SummaryBar";
import { PlanBar } from "./components/PlanBar";
import { ActualBar } from "./components/ActualBar";
import { OffsetBadge } from "./components/OffsetBadge";
import { MilestoneLines } from "./components/MilestoneLines";
import { GridLines } from "./components/GridLines";
import { type MilestoneMarker, type ReleaseMarker } from "./types";

/** 甘特面板:缩放/横滚;今日线、条、状态章(YPJ-249 自 page.tsx 原样搬出,零语义变化)。
 * renderGanttRow 壳(几何计算+组装)留此,条形子块已拆 components/。
 * As-built 偏差:axis prop 取结构子型(timelineW/headerRows/todayX),整体仍由 useTimelineScale 返回、page 持有;
 * setShowCompleted → onToggleCompleted(与 TitleColumn 同式);page 的 key → projectKey(href 逐字节不变);
 * scrollRef 由 page 持有传入(useGanttInteraction 消费)。 */
export function GanttChart({
  axis,
  mainRows,
  completedRows,
  scheduled,
  showCompleted,
  onToggleCompleted,
  collapsed,
  hasKid,
  summaryOf,
  descendantsOf,
  groupStartOf,
  xOf,
  xOfT,
  xEndOf,
  wd,
  pullDefault,
  scrollRef,
  projectKey,
  ms,
  rel,
}: {
  axis: { timelineW: number; headerRows: HeaderRow[]; todayX: number };
  mainRows: GanttRow[];
  completedRows: GanttRow[];
  scheduled: GanttRow[];
  showCompleted: boolean;
  onToggleCompleted: () => void;
  collapsed: Set<string>;
  hasKid: (r: GanttRow) => boolean;
  summaryOf: (itemId: string) => [string, string] | null;
  descendantsOf: (itemId: string) => number;
  groupStartOf: (r: GanttRow) => string | null;
  xOf: (date: string | null) => number | null;
  xOfT: (t: number) => number;
  xEndOf: (due: string | null) => number | null;
  wd: [number, number, boolean] | null;
  /** YPJ-216(SRS 4.4):甘特缺省画图输入——缺省预估(项目设置→中档)与组开始偏移分钟;仅绘制用,不写回票 */
  pullDefault: { estV: number; estU: string; offMin: number } | null;
  scrollRef: RefObject<HTMLDivElement | null>;
  projectKey: string;
  ms: MilestoneMarker[];
  rel: ReleaseMarker[];
}) {
  const renderGanttRow = (r: GanttRow) => {
    const isFinal = r.statusType === "FINAL";
    const summary = hasKid(r) ? summaryOf(r.itemId) : null;
    const summaryLeft = summary ? (xOf(summary[0]) ?? 0) : 0;
    const summaryWidth =
      summary !== null
        ? Math.max((xEndOf(summary[1]) ?? 0) - summaryLeft, 1.2)
        : 0;
    // 有子孙的父行只画汇总条,不画自有条
    const drawOwn = !hasKid(r);
    // YPJ-244(spec 0151 五):渲染规则 v2——case 2×2 分型 × 3 状态(用户 Obsidian 2026-11-20)
    const wsm = wd?.[0] ?? 0;
    const wem = wd?.[1] ?? 1440;
    // YPJ-216(SRS 4.4):无预估 → 项目缺省预估(仅绘制);无开始 → 组开始+偏移
    const estH =
      estimateHoursOf(r.estimateValue, r.estimateUnit, wsm, wem) ??
      (pullDefault
        ? estimateHoursOf(pullDefault.estV, pullDefault.estU, wsm, wem)
        : null);
    const offMs = (pullDefault?.offMin ?? 0) * 60_000;
    const startAnchor = r.startDate ?? r.anchorDate ?? null;
    const gS = groupStartOf(r);
    const caseNo =
      !estH && startAnchor === null
        ? 1
        : estH && startAnchor === null
          ? 2
          : !estH
            ? 3
            : 4;
    // SRS §6.6(YPJ-245):FINAL 后返工=当前状态优先——按 §6.2 进行中渲染(黄条延伸今天,零徽标)
    const rework = r.actualEnd !== null && r.statusType !== "FINAL";
    const doing = r.actualStart !== null && (r.actualEnd === null || rework);
    const dueOnly = startAnchor === null && r.dueDate !== null; // 里程碑菱形(既有,不进矩阵)
    // 上条锚:case3/4=start/依赖锚;case2=actualStart(借实际锚)或 groupStart(未开始借 group 锚);case1=仅未开始在 group 开始画点
    const planT =
      startAnchor !== null
        ? +parseLocal(startAnchor)
        : dueOnly
          ? +parseLocal(r.dueDate!)
          : estH !== null
            ? r.actualStart
              ? +parseLocal(r.actualStart)
              : gS
                ? +parseLocal(gS) + offMs
                : null
            : !r.actualStart && gS
              ? +parseLocal(gS) + offMs
              : null;
    // 条 vs 点:有长度来源(due 显式或 estimate 铺算)→ 条;仅锚无长度 → 点
    const planIsBar =
      planT !== null && !dueOnly && (r.dueDate !== null || estH !== null);
    const planEndT =
      planT === null || !planIsBar
        ? null
        : r.dueDate !== null
          ? +parseLocal(r.dueDate) +
            (/^\d{4}-\d{2}-\d{2}$/.test(r.dueDate) ? DAY : 0)
          : dueFromJs(planT, estH!, wsm, wem, wd?.[2] ?? false);
    const left = planT === null ? 0 : xOfT(planT);
    const right = planEndT === null ? left : xOfT(planEndT);
    // 零时长条最小 3px 可见(YPJ-229;due-only 菱形是标记不受影响)
    const width = planT !== null ? Math.max(right - left, 3) : 0;
    // YPJ-222(spec 0147):实际条(叶+有迁出史;due-only 行不画)+ 清单遮罩 + 偏移徽标
    // YPJ-244(spec 0151)H5:实际层与计划 start 解耦——actualStart 有即画
    const act = drawOwn && r.actualStart !== null;
    const actLeft = act ? (xOf(r.actualStart) ?? 0) : 0;
    const actRight = act
      ? Math.max(
          isFinal && r.actualEnd
            ? (xEndOf(r.actualEnd) ?? actLeft)
            : Math.max(axis.todayX, actLeft),
          actLeft,
        )
      : 0;
    const actWidth = act ? Math.max(actRight - actLeft, 3) : 0;
    const actColor = isFinal ? "success.main" : "warning.main"; // 五:颜色3 绿=完成实际 / 颜色2 黄=进行中实际(返工行黄,SRS §6.6)
    const actPct =
      r.checklistTotal && r.checklistDone !== null
        ? Math.min(Math.round((r.checklistDone / r.checklistTotal) * 100), 100)
        : null;
    // YPJ-255(spec 0159,2026-11-22 拍板):黄覆盖 = 进度,完全在计划条上、与日历/actualStart 无关——
    // 占比:清单完成率优先;无清单用工时/Estimate(estH 口径同计划条钟表长);皆无则无覆盖。A47:提前开工行(计划条在未来)覆盖照画(原时间流逝口径废除)。
    // 皆无则无覆盖。A47:提前开工行(计划条在未来)覆盖照画(原时间流逝口径废除)。
    // 超期仍由红条表达,不再「覆盖满条」(SRS §6.2 连带修订)。
    const estHours = estH;
    const fillPct =
      (r.checklistTotal ?? 0) > 0
        ? Math.min(100, (r.checklistDone! / r.checklistTotal!) * 100)
        : estHours !== null && r.totalMinutes > 0
          ? Math.min(100, (r.totalMinutes / 60 / estHours) * 100)
          : null;
    const fillW =
      doing && planIsBar && fillPct !== null ? (width * fillPct) / 100 : 0;
    // YPJ-244(spec 0151)H5:直建 FINAL(actualComplete 有而 actualStart 无)→ 完成点
    const completeDot =
      drawOwn && isFinal && r.actualStart === null && r.actualEnd !== null;
    const badge = offsetBadge(r);
    const badgeLeft =
      Math.max(planT !== null ? right : left + 12, act ? actRight : 0) + 6;
    return (
      <Box
        key={r.itemId}
        sx={{
          position: "relative",
          borderBottom: "1px solid",
          borderColor: "divider",
          minHeight: 44,
          display: "flex",
          alignItems: "center",
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        {/* 条区(固定像素宽;必须整行高——曾缺 stretch 致零高居中:今日线不可见、徽标/完成点溢出下一行,A44) */}
        <Box
          sx={{
            position: "relative",
            width: axis.timelineW,
            flexShrink: 0,
            flex: 1,
            alignSelf: "stretch",
          }}
        >
          <TodayLine todayX={axis.todayX} timelineW={axis.timelineW} />
          <SummaryBar
            summary={summary}
            summaryLeft={summaryLeft}
            summaryWidth={summaryWidth}
            collapsed={collapsed.has(r.itemId)}
            descendants={descendantsOf(r.itemId)}
            number={r.number}
          />
          <PlanBar
            r={r}
            visible={drawOwn && planT !== null}
            isFinal={isFinal}
            left={left}
            width={width}
            planIsBar={planIsBar}
            dueOnly={dueOnly}
            caseNo={caseNo}
            fillW={fillW}
          />
          <ActualBar
            visible={act && !(doing && planIsBar)}
            left={actLeft}
            width={actWidth}
            color={actColor}
            actPct={actPct}
            isFinal={isFinal}
            completeDot={completeDot}
            completeLeft={(xEndOf(r.actualEnd) ?? 0) - 5}
            number={r.number}
          />
          <OffsetBadge badge={badge} left={badgeLeft} number={r.number} />
          <Link
            href={`/p/${projectKey}/i/${r.itemId}?from=gantt`}
            style={{
              position: "absolute",
              left: 0,
              top: 0,
              right: 0,
              bottom: 0,
              display: "block",
            }}
            aria-label={r.number}
          >
            {/* hover 详情卡命中区 = 条/点矩形(YPJ-222;其余区域仍整块直进详情) */}
            {drawOwn &&
              (planT !== null ||
                (r.actualStart !== null &&
                  (r.actualEnd === null || rework))) && (
                <Tooltip title={<HoverCard r={r} wd={wd} />}>
                  <Box
                    data-testid={`gantt-hover-plan-${r.number}`}
                    sx={{
                      position: "absolute",
                      left,
                      top: 5,
                      width: Math.max(width, 12),
                      height: 12,
                    }}
                  />
                </Tooltip>
              )}
            {act && (
              <Tooltip title={<HoverCard r={r} wd={wd} />}>
                <Box
                  data-testid={`gantt-hover-act-${r.number}`}
                  sx={{
                    position: "absolute",
                    left: actLeft,
                    top: 24,
                    width: actWidth,
                    height: 12,
                  }}
                />
              </Tooltip>
            )}
            {summary && (
              <Tooltip title={<HoverCard r={r} wd={wd} />}>
                <Box
                  sx={{
                    position: "absolute",
                    left: summaryLeft,
                    top: 4,
                    width: summaryWidth,
                    height: 8,
                  }}
                />
              </Tooltip>
            )}
          </Link>
        </Box>

        <Chip
          label={r.statusName}
          size="small"
          variant="outlined"
          sx={{ mr: 1, height: 20, position: "sticky", right: 0 }}
        />
      </Box>
    );
  };

  return (
    <>
      {/* 甘特面板:缩放/横滚;今日线、条、状态章 */}
      <Box
        ref={scrollRef}
        data-testid="gantt-scroll"
        sx={{
          position: "relative",
          flex: 1,
          minWidth: 0,
          overflowX: "auto",
          overflowY: "hidden",
        }}
      >
        <Box sx={{ width: axis.timelineW, minWidth: "100%" }}>
          {/* header 自适应 2–3 行(月/周/日/时,自上而下粗→细) */}
          <TimelineHeader headerRows={axis.headerRows} />

          {/* YPJ-221:旗标带+竖线的定位基准(线 top:0→bottom:0 覆盖带+行区,含完成组) */}
          <Box sx={{ position: "relative" }}>
            {/* YPJ-239:日/周界淡分割线,条下层 */}
            <GridLines headerRows={axis.headerRows} />
            <MilestoneLines
              ms={ms}
              rel={rel}
              xOf={xOf}
              projectKey={projectKey}
            />

            {scheduled.length === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
                暂无已排期 item——在详情页填起止日期后出现在这里。
              </Typography>
            )}

            {mainRows.map(renderGanttRow)}
            {completedRows.length > 0 && (
              <Box
                sx={{
                  minHeight: 44,
                  borderBottom: "1px solid",
                  borderColor: "divider",
                  display: "flex",
                  alignItems: "center",
                }}
              >
                <Button
                  size="small"
                  data-testid="gantt-completed-group-bar"
                  onClick={onToggleCompleted}
                  sx={{ ml: 1 }}
                >
                  {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
                </Button>
              </Box>
            )}
            {showCompleted && completedRows.map((r) => renderGanttRow(r))}
          </Box>
        </Box>
      </Box>
    </>
  );
}
