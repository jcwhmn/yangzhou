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
import { parseLocal } from "@/lib/dates";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";

import { DAY, ZOOM_STEP, type GanttRow } from "./types";
import { useGanttData } from "./hooks/useGanttData";
import { useEnvelope } from "./hooks/useEnvelope";
import { useGanttInteraction } from "./hooks/useGanttInteraction";
import { useTimelineScale } from "./hooks/useTimelineScale";
import { HoverCard } from "./components/HoverCard";
import { Splitter, TitleColumn } from "./components/TitleColumn";
import { TimelineHeader } from "./components/TimelineHeader";
import { UnscheduledGroup } from "./components/UnscheduledGroup";
import { TodayLine } from "./components/TodayLine";
import { SummaryBar } from "./components/SummaryBar";
import { PlanBar } from "./components/PlanBar";
import { ActualBar } from "./components/ActualBar";
import { OffsetBadge } from "./components/OffsetBadge";
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
  // 包络/折叠分组(CP2c:summaryOf 系与未排期/已完成分组收进 useEnvelope;collapsed 状态留入口)
  const {
    summaryOf,
    descendantsOf,
    visible,
    hasKid,
    unscheduled,
    scheduled,
    completedRows,
    mainRows,
  } = useEnvelope({ rows, wd, groupStartOf, collapsed });
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

  // YPJ-222(spec 0147):条上 hover 详情卡;起止显时分、工时按小时(YPJ-244:数据含时分)

  function toggle(itemId: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  }

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
            actPct={actPct}
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
          <UnscheduledGroup
            unscheduled={unscheduled}
            showUnscheduled={showUnscheduled}
            onToggle={() => setShowUnscheduled((v) => !v)}
            projectKey={key}
          />

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
            <TitleColumn
              titleW={titleW}
              headerRows={axis.headerRows}
              mainRows={mainRows}
              completedRows={completedRows}
              showCompleted={showCompleted}
              onToggleCompleted={() => setShowCompleted((v) => !v)}
              collapsed={collapsed}
              toggle={toggle}
              hasKid={hasKid}
              projectKey={key}
            />
            <Splitter
              titleW={titleW}
              splitting={splitting}
              onSplitMouseDown={onSplitMouseDown}
              persistTitleW={persistTitleW}
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
                <TimelineHeader headerRows={axis.headerRows} />

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
}
