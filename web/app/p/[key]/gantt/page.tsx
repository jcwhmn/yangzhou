"use client";

import {
  Autocomplete,
  Button,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import { useParams } from "next/navigation";
import { useRef, useState } from "react";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";

import { useGanttData } from "./hooks/useGanttData";
import { useEnvelope } from "./hooks/useEnvelope";
import { useGanttInteraction } from "./hooks/useGanttInteraction";
import { useTimelineScale } from "./hooks/useTimelineScale";
import { Splitter, TitleColumn } from "./components/TitleColumn";
import { UnscheduledGroup } from "./components/UnscheduledGroup";
import { ZOOM_STEP } from "./types";
import { GanttChart } from "./GanttChart";
import { markerDatesOf } from "./components/MilestoneLines";
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
  const { axis, zoomTo, shiftCenter, centerOnToday, xOf, xOfT, xEndOf } =
    useTimelineScale({
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
      markerDates: markerDatesOf(msOpts, relOpts),
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
            <GanttChart
              axis={axis}
              mainRows={mainRows}
              completedRows={completedRows}
              scheduled={scheduled}
              showCompleted={showCompleted}
              onToggleCompleted={() => setShowCompleted((v) => !v)}
              collapsed={collapsed}
              hasKid={hasKid}
              summaryOf={summaryOf}
              descendantsOf={descendantsOf}
              groupStartOf={groupStartOf}
              xOf={xOf}
              xOfT={xOfT}
              xEndOf={xEndOf}
              wd={wd}
              scrollRef={scrollRef}
              projectKey={key}
              ms={msOpts}
              rel={relOpts}
            />
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
            · 「今日」回中今天 · 竖线 =
            里程碑/发布时点(虚=计划,实=已发布;旗标可点跳管理页)
          </Typography>
        </>
      )}
    </PageShell>
  );
}
