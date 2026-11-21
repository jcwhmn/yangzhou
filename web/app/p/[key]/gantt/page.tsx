"use client";

import {
  Autocomplete,
  Box,
  Button,
  Chip,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useRef, useState } from "react";
import { parseLocal, fmtDateTime } from "@/lib/dates";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";

import {
  DAY,
  EST_UNIT_SHORT,
  TITLE_W_DEFAULT,
  ZOOM_STEP,
  type GanttRow,
} from "./types";
import { useGanttData } from "./hooks/useGanttData";
import { useGanttInteraction } from "./hooks/useGanttInteraction";
import { useTimelineScale } from "./hooks/useTimelineScale";
import {
  dueFromJs,
  estimateHoursOf,
  offsetBadge,
} from "./utils/timeCalculations";
/** 单项目树形甘特(只读)。条 = start→due;只有 due 画里程碑菱形;今日线;未排期折叠组。 */
export default function GanttPage() {
  const { key } = useParams<{ key: string }>();
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  // YPJ-245(SRS §10.6):已完成折叠组,默认收起(会话态,不持久)
  const [showCompleted, setShowCompleted] = useState(false);
  const [showUnscheduled, setShowUnscheduled] = useState(false);
  // v2 工况:zoom(pxPerDay,null=自适应全区间)+ 视点日(日窗预设/今日的中心)+ 滚动位,sessionStorage 持久化
  // YPJ-220:范围 scope/scopeIds 同存同恢复(旧值缺省补 board)
  const savedView = (() => {
    try {
      return JSON.parse(sessionStorage.getItem("gantt.view") ?? "{}") as {
        zoom?: number;
        center?: string;
        scroll?: number;
        scope?: string;
        scopeIds?: string[];
      };
    } catch {
      return {};
    }
  })();
  const [zoom, setZoom] = useState<number | null>(savedView.zoom ?? null);
  const [center, setCenter] = useState(savedView.center ?? "");
  const [scope, setScope] = useState(savedView.scope ?? "board");
  const [scopeIds, setScopeIds] = useState<string[]>(savedView.scopeIds ?? []);
  // 数据获取与借锚(CP2a:rows/wd/二级选项随 fetch 收进 useGanttData,入口经返回值持有)
  const { rows, wd, groupOpts, msOpts, relOpts, groupStartOf } = useGanttData(
    key,
    scope,
    scopeIds,
  );
  const scrollRef = useRef<HTMLDivElement>(null);
  const {
    containerW,
    titleW,
    splitting,
    onSplitMouseDown,
    persistTitleW,
    saveView,
  } = useGanttInteraction({
    scrollRef,
    rows,
    savedView,
    zoom,
    center,
    scope,
    scopeIds,
  });
  const {
    axis,
    zoomTo,
    goToDayWindow,
    shiftCenter,
    centerOnToday,
    xOf,
    xOfT,
    xEndOf,
  } = useTimelineScale({
    rows,
    wd,
    zoom,
    setZoom,
    center,
    setCenter,
    containerW,
    scrollRef,
    groupStartOf,
    summaryOf,
    saveView,
  });

  const changeScope = (next: string) => {
    setScope(next);
    setScopeIds([]); // 切范围清空二级选择(spec 0141)
  };

  // SRS v4 §7.2(2026-11-21 拍板;第三方评审 P0-1 修订):父汇总条 = 当前过滤后可见子孙**叶行**
  // 渲染元素(计划条/计划点/实际条/实际点)的时间并集——从渲染结果反推,不从字段拼凑:
  //   计划条 = 锚(start/依赖锚/group 借锚)→(due 端[date-only 含当天] ?? 锚+Estimate 钟表长);仅锚无长度 → 计划点(零宽)
  //   实际条 = actualStart→(actualEnd ?? 今天);仅 actualEnd → 完成点(零宽,直建 FINAL)
  // 各级父行自有日期与自有状态史作废(§4.3),只继续下探;与排序锚/fit 同源口径(§7.3)。
  function summaryOf(itemId: string): [string, string] | null {
    let min: number | null = null;
    let max: number | null = null;
    const consider = (l: number, r: number) => {
      if (min === null || l < min) min = l;
      if (max === null || r > max) max = r;
    };
    const visit = (id: string) => {
      for (const x of rows ?? []) {
        if (x.parentItemId !== id) continue;
        if ((rows ?? []).some((k) => k.parentItemId === x.itemId)) {
          visit(x.itemId);
          continue;
        }
        const wsm = wd?.[0] ?? 0;
        const wem = wd?.[1] ?? 1440;
        const estH = estimateHoursOf(x.estimateValue, x.estimateUnit, wsm, wem);
        // 计划层元素
        const anchorRaw = x.startDate ?? x.anchorDate ?? groupStartOf(x);
        if (anchorRaw !== null) {
          const a = +parseLocal(anchorRaw);
          const dueEnd =
            x.dueDate !== null
              ? +parseLocal(x.dueDate) +
                (/^\d{4}-\d{2}-\d{2}$/.test(x.dueDate) ? DAY : 0)
              : null;
          const estEnd = estH !== null ? dueFromJs(a, estH, wsm, wem) : null;
          if (dueEnd !== null || estEnd !== null)
            consider(a, Math.max(a, dueEnd ?? estEnd!));
          else consider(a, a); // 计划点:零宽(case1 借锚点 / case3b)
        } else if (x.dueDate !== null) {
          // due-only 菱形(历史数据,Q11 废除后无新增):零宽点位参与包络
          const d = +parseLocal(x.dueDate);
          consider(d, d);
        }
        // 实际层元素
        if (x.actualStart !== null) {
          const s = +parseLocal(x.actualStart);
          const e =
            x.actualEnd !== null ? +parseLocal(x.actualEnd) : Date.now();
          consider(s, Math.max(s, e));
        } else if (x.actualEnd !== null) {
          const e = +parseLocal(x.actualEnd);
          consider(e, e); // 完成点:零宽(直建 FINAL,SRS §6.4)
        }
      }
    };
    visit(itemId);
    return min === null || max === null
      ? null
      : [new Date(min).toISOString(), new Date(max).toISOString()];
  }

  // YPJ-222(spec 0147):折叠徽标 N = 该父的全部后代数(任一深度)
  function descendantsOf(itemId: string): number {
    let n = 0;
    const visit = (id: string) => {
      for (const x of rows ?? []) {
        if (x.parentItemId !== id) continue;
        n++;
        visit(x.itemId);
      }
    };
    visit(itemId);
    return n;
  }

  // YPJ-222(spec 0147):条上 hover 详情卡;起止显时分、工时按小时(YPJ-244:数据含时分)
  function hoverCard(r: GanttRow) {
    const estH = estimateHoursOf(
      r.estimateValue,
      r.estimateUnit,
      wd?.[0] ?? 0,
      wd?.[1] ?? 1440,
    );
    const actH =
      r.actualStart && r.actualEnd
        ? Math.max(
            0,
            (+parseLocal(r.actualEnd) - +parseLocal(r.actualStart)) / 3600000,
          )
        : null;
    const fmtH = (h: number) => `${+h.toFixed(1)}h`;
    return (
      <Stack spacing={0.25}>
        <Typography variant="caption" sx={{ fontWeight: 600 }}>
          {r.number} {r.title}
        </Typography>
        <Typography variant="caption">
          {r.statusName}
          {r.assigneeName ? ` · ${r.assigneeName}` : ""}
        </Typography>
        <Typography variant="caption">
          计划:{r.startDate ? fmtDateTime(r.startDate) : "?"} →{" "}
          {r.dueDate ? fmtDateTime(r.dueDate) : "?"}
        </Typography>
        {estH !== null && (
          <Typography variant="caption">预估工时:{fmtH(estH)}</Typography>
        )}
        {r.actualStart && (
          <Typography variant="caption">
            实际:{fmtDateTime(r.actualStart)} →{" "}
            {r.actualEnd && r.statusType === "FINAL"
              ? fmtDateTime(r.actualEnd)
              : "进行中"}
            {actH !== null ? ` · 工时 ${fmtH(actH)}` : ""}
          </Typography>
        )}
        {r.actualEnd && r.statusType !== "FINAL" && (
          <Typography variant="caption">
            上次完成:{fmtDateTime(r.actualEnd)}(返工中,SRS §6.6)
          </Typography>
        )}
        {r.anchorDate && (
          <Typography variant="caption">
            锚点:{fmtDateTime(r.anchorDate)}(依赖落位)
          </Typography>
        )}
        {r.checklistTotal ? (
          <Typography variant="caption">
            清单:{r.checklistDone ?? 0}/{r.checklistTotal}
          </Typography>
        ) : null}
      </Stack>
    );
  }

  function toggle(itemId: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  }

  // 折叠语义:折叠某个父,隐藏其全部后代
  function hiddenByCollapse(r: GanttRow): boolean {
    let cursor = r.parentItemId;
    const byId = new Map((rows ?? []).map((x) => [x.itemId, x]));
    while (cursor) {
      if (collapsed.has(cursor)) return true;
      cursor = byId.get(cursor)?.parentItemId ?? null;
    }
    return false;
  }

  const visible = (rows ?? []).filter((r) => !hiddenByCollapse(r));
  // 有子孙的父归入已排期(靠汇总条画);锚点行(YPJ-244:依赖落位画点)与进行中行(actualStart 有,画计划点/实际条)也归已排期;真未排期 = 无日期无子无锚无 actual
  const hasKid = (r: GanttRow) =>
    (rows ?? []).some((x) => x.parentItemId === r.itemId);
  // 五:case1/2 借 group 开始锚也归已排期;真未排期 = 全链无锚无 actual 叶行
  // SRS v4 §5.4(评审 P0-2 修订):计划层与实际层均无任何可渲染元素才入未排期组(actualEnd 也要查——直建 FINAL 完成点须在主面板渲染)
  const unscheduled = visible.filter(
    (r) =>
      !r.startDate &&
      !r.dueDate &&
      !hasKid(r) &&
      !r.anchorDate &&
      !r.actualStart &&
      !r.actualEnd &&
      !groupStartOf(r),
  );
  const scheduled = visible.filter(
    (r) =>
      r.startDate ||
      r.dueDate ||
      hasKid(r) ||
      r.anchorDate ||
      r.actualStart ||
      groupStartOf(r),
  );

  // YPJ-245(SRS §10.6):FINAL 行摘入「已完成(N)」组;父行仅当整子树 FINAL 才摘(防孤儿断树)
  function isCompletedRow(r: GanttRow): boolean {
    if (r.statusType !== "FINAL") return false;
    return (rows ?? [])
      .filter((x) => x.parentItemId === r.itemId)
      .every((x) => isCompletedRow(x));
  }
  const completedRows = scheduled.filter((r) => isCompletedRow(r));
  const mainRows = scheduled.filter((r) => !isCompletedRow(r));

  // YPJ-245(SRS §10.6):行渲染器抽出——主树与「已完成(N)」组复用同一渲染(双面板同步)
  const renderTitleCell = (r: GanttRow) => (
    <Box
      key={r.itemId}
      sx={{
        borderBottom: "1px solid",
        borderColor: "divider",
        minHeight: 44,
        display: "flex",
        alignItems: "center",
        "&:hover": { bgcolor: "action.hover" },
      }}
    >
      <Stack
        direction="row"
        spacing={0.5}
        alignItems="center"
        sx={{ pl: 1 + r.depth * 2, py: 0.5 }}
      >
        {hasKid(r) && (
          <Button
            size="small"
            onClick={() => toggle(r.itemId)}
            sx={{ minWidth: 0, px: 0.5 }}
          >
            {collapsed.has(r.itemId) ? "▶" : "▼"}
          </Button>
        )}
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题可点进详情;自然宽不截断,列内横滚(YPJ-236) */}
        <Link
          href={`/p/${key}/i/${r.itemId}?from=gantt`}
          style={{ minWidth: 0 }}
        >
          {/* component=div:inline span 吃不到盒模型属性,nowrap 文字会画出盒叠压 chip(YPJ-230) */}
          <Typography
            component="div"
            data-testid={`gantt-title-${r.number}`}
            variant="caption"
            noWrap
            sx={{
              color: "text.primary",
              textDecoration: "underline",
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
        {r.estimateValue !== null && (
          <Chip
            data-testid={`gantt-est-${r.number}`}
            label={`${r.estimateValue}${EST_UNIT_SHORT[r.estimateUnit ?? ""] ?? r.estimateUnit ?? ""}`}
            size="small"
            variant="outlined"
            sx={{ height: 18 }}
          />
        )}
        {r.blocked && (
          <Chip
            data-testid={`gantt-blocked-${r.number}`}
            label="🔗 blocked"
            size="small"
            color="warning"
            sx={{ height: 18 }}
          />
        )}
      </Stack>
    </Box>
  );

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
    const estH = estimateHoursOf(r.estimateValue, r.estimateUnit, wsm, wem);
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
                ? +parseLocal(gS)
                : null
            : !r.actualStart && gS
              ? +parseLocal(gS)
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
          : dueFromJs(planT, estH!, wsm, wem);
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
    // YPJ-244(五)黄覆盖:进行中在计划条上叠「已流逝」段(末端=min(今天, 条尾));
    // 覆盖条件=计划条存在且有 estimate(due 显式无 est 不覆盖,同用户 case3 字面);
    // 周六日照算现状下日历时间位置等价工时比例;双休日历(H8)落地时改 hoursBetween 比例换算
    // SRS v4 §6.2(2026-11-21 拍板:进行中单条覆盖模型):凡有计划条的进行中行都画
    // 黄覆盖(条内已流逝段,start→min(今天,条尾),方案 A 口径);无第二条实际条
    const fillW =
      doing && planIsBar
        ? Math.min(Math.max(Math.min(axis.todayX, right) - left, 0), width)
        : 0;
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
          {axis.todayX >= 0 && axis.todayX <= axis.timelineW && (
            <Box
              sx={{
                position: "absolute",
                left: axis.todayX,
                top: 0,
                bottom: 0,
                width: "2px",
                bgcolor: "info.main",
                opacity: 0.5,
              }}
            />
          )}
          <Box
            data-summary={summary ? "" : undefined}
            sx={{
              position: "absolute",
              left: summaryLeft,
              width: summaryWidth,
              top: 4,
              display: summary ? "block" : "none",
            }}
          >
            <Box
              sx={{
                height: 8,
                borderRadius: 1,
                bgcolor: "warning.main", // YPJ-222:配色重整——汇总条灰→橙(spec 0147 Q3)
                opacity: 0.75,
              }}
            />
          </Box>
          {/* 折叠聚合徽标(YPJ-222) */}
          {summary && collapsed.has(r.itemId) && (
            <Chip
              label={`+${descendantsOf(r.itemId)} 子任务`}
              size="small"
              data-testid={`gantt-collapsed-${r.number}`}
              sx={{
                position: "absolute",
                left: summaryLeft + summaryWidth + 4,
                top: 0,
                height: 16,
                fontSize: 10,
              }}
            />
          )}
          <Box
            data-final={isFinal ? "" : undefined}
            data-testid={`gantt-bar-${r.number}`}
            sx={{
              position: "absolute",
              left,
              width,
              top: 5,
              display: drawOwn && planT !== null ? "block" : "none",
            }}
          >
            {planIsBar ? (
              <Box
                sx={{
                  position: "relative",
                  height: 12,
                  borderRadius: 1,
                  bgcolor: r.overdue ? "error.main" : "primary.main",
                  opacity: 0.85,
                }}
              >
                {fillW > 0 && (
                  <Box
                    data-testid={`gantt-fill-${r.number}`}
                    sx={{
                      position: "absolute",
                      left: 0,
                      top: 0,
                      height: "100%",
                      width: fillW,
                      borderRadius: fillW >= width ? 1 : "4px 0 0 4px",
                      bgcolor: "warning.main",
                      opacity: 0.9,
                    }}
                  >
                    {/* 清单完成率遮罩:画在黄覆盖段上(基准=覆盖段宽,SRS §6.5) */}
                    {actPct !== null && (
                      <Box
                        sx={{
                          position: "absolute",
                          left: 0,
                          top: 0,
                          height: "100%",
                          width: `${actPct}%`,
                          bgcolor: "warning.dark",
                        }}
                      />
                    )}
                  </Box>
                )}
              </Box>
            ) : dueOnly ? (
              <Box
                sx={{
                  width: 12,
                  height: 12,
                  transform: "rotate(45deg)",
                  bgcolor: r.overdue ? "error.main" : "warning.main",
                  opacity: 1,
                  mx: "auto",
                }}
              />
            ) : (
              // spec 0151 五:case1/3 计划层圆点——位置=group 开始(case1 未开始)/ start 或依赖锚(case3)
              <Box
                data-testid={`gantt-${caseNo === 1 ? "grouppoint" : r.anchorDate && r.startDate === null ? "anchor" : "plandot"}-${r.number}`}
                sx={{
                  width: 3,
                  height: 12,
                  borderRadius: 1,
                  bgcolor: "primary.main",
                }}
              />
            )}
          </Box>
          {/* 实际条(下条):仅 无计划条的进行中(case1/3b 黄条)/ 完成(绿条)/ 返工 渲染;
                            有计划条的进行中行 = 单条覆盖模型,不再画第二条(SRS v4 §6.2,2026-11-21 用户拍板) */}
          {act && !(doing && planIsBar) && (
            <Box
              data-testid={`gantt-actual-${r.number}`}
              sx={{
                position: "absolute",
                left: actLeft,
                width: actWidth,
                top: 24,
              }}
            >
              <Box
                sx={{
                  position: "relative",
                  height: 12,
                  borderRadius: 1,
                  bgcolor: actColor,
                  opacity: 0.85,
                }}
              >
                {actPct !== null && (
                  <Box
                    sx={{
                      position: "absolute",
                      left: 0,
                      top: 0,
                      height: "100%",
                      width: `${actPct}%`,
                      borderRadius: 1,
                      bgcolor: isFinal ? "success.dark" : "warning.dark",
                    }}
                  />
                )}
              </Box>
            </Box>
          )}
          {/* YPJ-244(spec 0151)H5:完成点(actualComplete 有而 actualStart 无) */}
          {completeDot && (
            <Box
              data-testid={`gantt-complete-${r.number}`}
              sx={{
                position: "absolute",
                left: (xEndOf(r.actualEnd) ?? 0) - 5,
                top: 26,
                width: 10,
                height: 10,
                borderRadius: "50%",
                bgcolor: isFinal ? "text.disabled" : "success.main",
                opacity: isFinal ? 0.5 : 0.85,
              }}
            />
          )}
          {/* 偏移徽标:仅 FINAL;On time 中性 / ±Nh / ±Nd,提前绿/延期红(SRS §11.1,YPJ-245) */}
          {badge !== null && (
            <Typography
              variant="caption"
              data-testid={`gantt-offset-${r.number}`}
              sx={{
                position: "absolute",
                left: badgeLeft,
                top: 24,
                lineHeight: "12px",
                fontWeight: 600,
                color: badge.color,
                whiteSpace: "nowrap",
              }}
            >
              {badge.label}
            </Typography>
          )}
          <Link
            href={`/p/${key}/i/${r.itemId}?from=gantt`}
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
                <Tooltip title={hoverCard(r)}>
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
              <Tooltip title={hoverCard(r)}>
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
              <Tooltip title={hoverCard(r)}>
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
    <PageShell>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{`${String(key)} · 甘特`}</Typography>
        <Stack
          direction="row"
          spacing={0.5}
          sx={{ ml: "auto" }}
          alignItems="center"
        >
          {/* 范围选择器(YPJ-220):五选 + 二级对象选择 */}
          <Select
            size="small"
            data-testid="gantt-scope"
            value={scope}
            onChange={(e) => changeScope(e.target.value)}
            sx={{ minWidth: 96 }}
            aria-label="范围"
          >
            <MenuItem value="board">看板</MenuItem>
            <MenuItem value="group">分组</MenuItem>
            <MenuItem value="milestone">里程碑</MenuItem>
            <MenuItem value="release">发布</MenuItem>
            <MenuItem value="all">全部</MenuItem>
          </Select>
          {scope === "group" && (
            <Autocomplete
              multiple
              disableCloseOnSelect // 多选:选完一项弹层保持,继续选下一项
              size="small"
              data-testid="gantt-scope-groups"
              options={groupOpts}
              getOptionLabel={(o) => o.name}
              isOptionEqualToValue={(a, b) => a.groupId === b.groupId}
              value={groupOpts.filter((g) => scopeIds.includes(g.groupId))}
              onChange={(_, v) => setScopeIds(v.map((g) => g.groupId))}
              renderInput={(params) => (
                <TextField
                  {...params}
                  placeholder="选择分组"
                  sx={{ minWidth: 180 }}
                />
              )}
            />
          )}
          {(scope === "milestone" || scope === "release") && (
            <Select
              size="small"
              displayEmpty
              data-testid={
                scope === "milestone"
                  ? "gantt-scope-milestone"
                  : "gantt-scope-release"
              }
              value={scopeIds[0] ?? ""}
              onChange={(e) =>
                setScopeIds(e.target.value ? [e.target.value] : [])
              }
              sx={{ minWidth: 160 }}
              aria-label="范围对象"
            >
              <MenuItem value="" disabled>
                {scope === "milestone" ? "选择里程碑" : "选择发布"}
              </MenuItem>
              {(scope === "milestone"
                ? msOpts.map((o) => ({ id: o.milestoneId, name: o.name }))
                : relOpts.map((o) => ({ id: o.releaseId, name: o.name }))
              ).map((o) => (
                <MenuItem key={o.id} value={o.id}>
                  {o.name}
                </MenuItem>
              ))}
            </Select>
          )}
          {/* 缩放控件(v2 连续系数):− / + ;Ctrl+滚轮亦可(锚点=鼠标) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-zoom-out"
            aria-label="缩小"
            onClick={() => zoomTo(axis.pxPerDay / ZOOM_STEP)}
          >
            −
          </Button>
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-zoom-in"
            aria-label="放大"
            onClick={() => zoomTo(axis.pxPerDay * ZOOM_STEP)}
          >
            +
          </Button>
          {/* 全览:回到缺省视窗(fit+下限;宽区间下不再压满一屏,YPJ-234) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-fit"
            onClick={() => {
              setZoom(null);
              setCenter("");
            }}
          >
            全览
          </Button>
          {/* 今日回中(YPJ-234 案乙:只居中不重置缩放;回缺省缩放用「全览」) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-today"
            onClick={centerOnToday}
          >
            今日
          </Button>
          {/* 日窗预设(视点日为中心,N 天撑满) */}
          {[1, 2, 3, 5].map((n) => (
            <Button
              key={n}
              size="small"
              variant="outlined"
              data-testid={`gantt-win-${n}`}
              onClick={() => goToDayWindow(n)}
            >
              {n}日
            </Button>
          ))}
          {/* 视点日导航:◀ 前一天 / 后一天 ▶ / 指定日期 */}
          <Stack
            direction="row"
            spacing={0.5}
            alignItems="center"
            data-testid="gantt-center-nav"
          >
            <Button
              size="small"
              variant="outlined"
              data-testid="gantt-center-prev"
              onClick={() => shiftCenter(-1)}
            >
              ◀
            </Button>
            <DateInput
              ariaLabel="视点日"
              size="small"
              value={center}
              onChange={(v) => setCenter(v)}
              sx={{ width: 160 }}
            />
            <Button
              size="small"
              variant="outlined"
              data-testid="gantt-center-next"
              onClick={() => shiftCenter(1)}
            >
              ▶
            </Button>
          </Stack>
        </Stack>
      </Stack>

      {rows === null ? (
        <Typography color="text.secondary">加载中…</Typography>
      ) : rows.length === 0 ? (
        <Typography color="text.secondary">{t.projects.empty}</Typography>
      ) : (
        <>
          {/* 未排期折叠组 */}
          {unscheduled.length > 0 && (
            <Box sx={{ mb: 2 }}>
              <Button
                size="small"
                variant="outlined"
                onClick={() => setShowUnscheduled((v) => !v)}
              >
                {showUnscheduled ? "▼" : "▶"} 未排期({unscheduled.length})
              </Button>
              {showUnscheduled &&
                unscheduled.map((r) => (
                  <Box key={r.itemId} sx={{ pl: 2 + r.depth * 2, mt: 0.5 }}>
                    <RowTitle r={r} />
                  </Box>
                ))}
            </Box>
          )}

          {/* 双面板(YPJ-236):标题列独立横滚+右边线+异色底;甘特区缩放横滚;纵向行对齐 */}
          <Stack
            direction="row"
            sx={{
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 1,
              overflow: "hidden",
              alignItems: "stretch",
            }}
          >
            {/* 标题面板:标题按自然宽呈现,超宽时列内底部横滚(YPJ-236) */}
            <Box
              data-testid="gantt-titlepane"
              sx={{
                width: titleW,
                flexShrink: 0,
                overflowX: "auto",
                overflowY: "hidden",
                bgcolor: "grey.50",
                borderRight: "1px solid",
                borderColor: "divider",
              }}
            >
              <Box sx={{ width: "max-content", minWidth: "100%" }}>
                {/* 表头占位:与甘特区刻度行同高同边框,保证行对齐 */}
                {axis.headerRows.map((row) => (
                  <Box
                    key={row.gran}
                    sx={{
                      height: 24,
                      borderBottom: "1px solid",
                      borderColor: "divider",
                    }}
                  />
                ))}
                {mainRows.map(renderTitleCell)}
                {completedRows.length > 0 && (
                  <Box
                    sx={{
                      borderBottom: "1px solid",
                      borderColor: "divider",
                      minHeight: 44,
                      display: "flex",
                      alignItems: "center",
                    }}
                  >
                    <Button
                      size="small"
                      data-testid="gantt-completed-group"
                      onClick={() => setShowCompleted((v) => !v)}
                      sx={{ pl: 1 }}
                    >
                      {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
                    </Button>
                  </Box>
                )}
                {showCompleted && completedRows.map((r) => renderTitleCell(r))}
              </Box>
            </Box>
            {/* 237 分割线:骑在标题列右边线上的透明热区,hover/拖拽/键盘焦点显 info.main 竖线 */}
            <Box
              data-testid="gantt-splitter"
              role="separator"
              aria-orientation="vertical"
              aria-label="调整标题列宽度"
              tabIndex={0}
              onMouseDown={onSplitMouseDown}
              onDoubleClick={() => persistTitleW(TITLE_W_DEFAULT)}
              onKeyDown={(e) => {
                if (e.key === "ArrowLeft") {
                  e.preventDefault();
                  persistTitleW(titleW - 16);
                } else if (e.key === "ArrowRight") {
                  e.preventDefault();
                  persistTitleW(titleW + 16);
                }
              }}
              sx={{
                width: 9,
                flexShrink: 0,
                ml: "-5px", // 骑上右边线;不用 sx 数字(被 theme spacing 放大,实锤 -35px)
                position: "relative",
                zIndex: 1,
                cursor: "col-resize",
                "&::after": {
                  content: '""',
                  position: "absolute",
                  top: 0,
                  bottom: 0,
                  left: 3.5,
                  width: 2,
                  bgcolor: splitting ? "info.main" : "transparent",
                },
                "&:hover::after, &:focus-visible::after": {
                  bgcolor: "info.main",
                },
              }}
            />
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
                {axis.headerRows.map((row) => (
                  <Box
                    key={row.gran}
                    data-testid={`gantt-row-${row.gran}`}
                    sx={{
                      position: "relative",
                      height: 24,
                      borderBottom: "1px solid",
                      borderColor: "divider",
                      bgcolor: "background.default",
                    }}
                  >
                    {row.ticks.map((tk) => (
                      <Typography
                        key={tk.key}
                        variant="caption"
                        color="text.secondary"
                        sx={{
                          position: "absolute",
                          left: tk.x + 4,
                          top: 2,
                          whiteSpace: "nowrap",
                        }}
                      >
                        {tk.label}
                      </Typography>
                    ))}
                  </Box>
                ))}

                {scheduled.length === 0 && (
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ p: 2 }}
                  >
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
                      onClick={() => setShowCompleted((v) => !v)}
                      sx={{ ml: 1 }}
                    >
                      {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
                    </Button>
                  </Box>
                )}
                {showCompleted && completedRows.map((r) => renderGanttRow(r))}
              </Box>
            </Box>
          </Stack>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ mt: 1, display: "block" }}
          >
            蓝竖线 = 今天 · 菱形 = 里程碑(只有截止日期) · 红色 = 超期 · 橙横条 =
            父汇总(子孙并集,折叠显 +N 子任务) · 列 = 24h 钟表轴(条长 ∝ 钟表跨度)
            · 上条 = 计划(蓝) / 下条 = 实际 (绿,深色 = 清单完成率) · 绿填充 =
            已流逝(进行中,今天 vs due,用实际 色覆盖计划) · 蓝点 =
            依赖锚点落位(无 start 无 due) · On time/±Nh/±Nd =
            偏移徽标(仅完成行,提前绿/延期红) · 悬停条看详情 · Ctrl+滚轮或 ± 缩放
            · 「今日」回中今天
          </Typography>
        </>
      )}
    </PageShell>
  );

  function RowTitle({ r }: { r: GanttRow }) {
    return (
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题列可点进详情(条形区另有整块隐形覆盖链接,YPJ-220 反馈补) */}
        <Link href={`/p/${key}/i/${r.itemId}?from=gantt`}>
          <Typography
            variant="caption"
            color="text.primary"
            noWrap
            sx={{
              textDecoration: "underline",
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
      </Stack>
    );
  }
}
