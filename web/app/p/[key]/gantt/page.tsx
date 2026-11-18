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
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "@/lib/api";
import { parseLocal } from "@/lib/dates";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";

type GanttRow = {
  itemId: string;
  parentItemId: string | null;
  number: string;
  title: string;
  startDate: string | null;
  dueDate: string | null;
  statusName: string;
  statusType: string | null;
  overdue: boolean;
  blocked: boolean;
  estimateValue: number | null;
  estimateUnit: string | null;
  depth: number;
};

const DAY = 24 * 60 * 60 * 1000;

// YPJ-219 v2(spec 0140):轴 = 数据锚定(首日 00:00 → 末日 24:00),连续缩放,无语义档位
const TITLE_W = 280;
const MAX_PPD = 720; // 缩放上限:1h = 30px(细节级)
const MIN_LABEL_PX = 44; // 刻度标签最小间距
const ZOOM_STEP = 1.5; // ± 按钮步进系数

type Tick = { x: number; label: string; key: string };
type HeaderRow = { gran: "month" | "week" | "day" | "hour"; ticks: Tick[] };

// estimateUnit 封闭枚举(hour/day/week)的短标(YPJ-204)
const EST_UNIT_SHORT: Record<string, string> = {
  hour: "h",
  day: "d",
  week: "w",
};

/** 单项目树形甘特(只读)。条 = start→due;只有 due 画里程碑菱形;今日线;未排期折叠组。 */
export default function GanttPage() {
  const { key } = useParams<{ key: string }>();
  const [rows, setRows] = useState<GanttRow[] | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
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
  const [containerW, setContainerW] = useState(0); // 可视宽(内容至少撑满一屏)
  const scrollRef = useRef<HTMLDivElement>(null);
  const loadSeqRef = useRef(0); // 响应序号守卫:切范围后慢响应晚到不覆盖新状态

  // 范围二级选项(group/milestone/release;列表 API 均返回裸数组)
  const [groupOpts, setGroupOpts] = useState<
    { groupId: string; name: string }[]
  >([]);
  const [msOpts, setMsOpts] = useState<{ milestoneId: string; name: string }[]>(
    [],
  );
  const [relOpts, setRelOpts] = useState<{ releaseId: string; name: string }[]>(
    [],
  );

  const load = useCallback(async () => {
    // 未选完不发请求:group 空多选 / milestone、release 未选中 → 直接空态
    if (
      scope === "group"
        ? scopeIds.length === 0
        : (scope === "milestone" || scope === "release") &&
          scopeIds.length !== 1
    ) {
      setRows([]);
      return;
    }
    const qs = new URLSearchParams();
    if (scope !== "board") qs.set("scope", scope);
    if (scopeIds.length > 0) qs.set("scopeIds", scopeIds.join(","));
    const seq = ++loadSeqRef.current;
    const q = qs.toString();
    const data = await api<{ rows: GanttRow[] }>(
      `/api/projects/${key}/gantt${q ? `?${q}` : ""}`,
    );
    if (seq !== loadSeqRef.current) return; // 已有更新的范围请求,丢弃过期响应
    setRows(data.rows);
  }, [key, scope, scopeIds]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 二级选项一次拉取(失败静默:选择器留空,不阻塞甘特)
  useEffect(() => {
    api<{ groupId: string; name: string }[]>(`/api/projects/${key}/groups`)
      .then(setGroupOpts)
      .catch(() => {});
    api<{ milestoneId: string; name: string }[]>(
      `/api/projects/${key}/milestones`,
    )
      .then(setMsOpts)
      .catch(() => {});
    api<{ releaseId: string; name: string }[]>(`/api/projects/${key}/releases`)
      .then(setRelOpts)
      .catch(() => {});
  }, [key]);

  const changeScope = (next: string) => {
    setScope(next);
    setScopeIds([]); // 切范围清空二级选择(spec 0141)
  };

  // 轴(YPJ-219 spec 0140 v2):范围 = 数据锚定日界;pxPerDay 连续 ∈ [fit 全区间压一屏, MAX_PPD]
  const axis = useMemo(() => {
    const today = new Date();
    const rowsNow = rows ?? [];
    // 轴数据锚定与画条同口径(spec 0139/0140):父行自有日期忽略不画,取子孙排期并集;子行取自有日期
    const isParent = (r: GanttRow) =>
      rowsNow.some((x) => x.parentItemId === r.itemId);
    const dates = rowsNow.flatMap((r) => {
      if (!isParent(r))
        return [r.startDate, r.dueDate].filter(Boolean) as string[];
      const s = summaryOf(r.itemId);
      return s ? [s[0], s[1]] : [];
    });
    const minT = dates.length
      ? Math.min(...dates.map((d) => +parseLocal(d)))
      : +today;
    const maxT = dates.length
      ? Math.max(...dates.map((d) => +parseLocal(d)))
      : +today;
    const start = new Date(minT);
    start.setHours(0, 0, 0, 0);
    const end = new Date(maxT);
    end.setHours(0, 0, 0, 0);
    const endT = +end + DAY; // 末日 24:00
    const spanDays = Math.max((endT - +start) / DAY, 1);
    const fit = containerW > TITLE_W ? (containerW - TITLE_W) / spanDays : 20;
    const pxPerDay = Math.min(Math.max(zoom ?? fit, fit), MAX_PPD);
    const timelineW = spanDays * pxPerDay;
    const xOfT = (t: number) => ((t - +start) / DAY) * pxPerDay;

    // 两级刻度:细刻度(小时梯子 1/2/3/6/12h,取间距≥MIN_LABEL_PX 的最小步长);粗行(月/周/日)按段宽门控
    const rowsOut: HeaderRow[] = [];
    const pxPerHour = pxPerDay / 24;
    // 小时行:梯子取最小步长使间距≥MIN_LABEL_PX;无解(极度缩小时)整行丢弃,不吐过密标签
    const stepH = [1, 2, 3, 6, 12].find((s) => s * pxPerHour >= MIN_LABEL_PX);
    if (pxPerDay >= MIN_LABEL_PX && stepH) {
      const ticks: Tick[] = [];
      for (let h = 0; h <= spanDays * 24; h += stepH) {
        const d = new Date(+start + (h * DAY) / 24);
        const label = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
        ticks.push({
          x: xOfT(+d),
          label,
          key: `h${h}`,
        });
      }
      rowsOut.push({ gran: "hour", ticks });
    }
    // 日行:每天一格 MM-DD
    if (pxPerDay >= MIN_LABEL_PX) {
      const ticks: Tick[] = [];
      for (let t = +start; t < endT; t += DAY) {
        const d = new Date(t);
        ticks.push({
          x: xOfT(t),
          label: `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
          key: `d${t}`,
        });
      }
      rowsOut.push({ gran: "day", ticks });
    }
    // 周行:周一 Wnn
    if (pxPerDay * 7 >= 90) {
      const first = new Date(+start);
      const dow = (first.getDay() + 6) % 7;
      first.setDate(first.getDate() - dow);
      const ticks: Tick[] = [];
      for (let t = +first; t < endT; t += 7 * DAY) {
        const d = new Date(t);
        const week = Math.ceil(
          ((+d - +new Date(d.getFullYear(), 0, 1)) / DAY + 1) / 7,
        );
        ticks.push({
          x: xOfT(t),
          label: `W${String(week).padStart(2, "0")}`,
          key: `w${t}`,
        });
      }
      rowsOut.push({ gran: "week", ticks });
    }
    // 月行:日界月首 YYYY-MM
    if (pxPerDay * 30 >= 70) {
      const ticks: Tick[] = [];
      const cur = new Date(+start);
      cur.setDate(1);
      while (+cur < endT) {
        ticks.push({
          x: xOfT(+cur),
          label: `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`,
          key: `m${+cur}`,
        });
        cur.setMonth(cur.getMonth() + 1);
      }
      rowsOut.push({ gran: "month", ticks });
    }
    // 保底:极缩小时只留月行(抽稀)
    if (rowsOut.length === 0) {
      const stepDays = Math.ceil((MIN_LABEL_PX * 1.5) / pxPerDay / 30) * 30;
      const ticks: Tick[] = [];
      const cur = new Date(+start);
      cur.setDate(1);
      let i = 0;
      while (+cur < endT) {
        if (i % Math.max(stepDays / 30, 1) === 0)
          ticks.push({
            x: xOfT(+cur),
            label: `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`,
            key: `m${+cur}`,
          });
        cur.setMonth(cur.getMonth() + 1);
        i++;
      }
      rowsOut.push({ gran: "month", ticks });
    }
    // 最多 3 行:自顶(最粗)丢弃;渲染序 = 粗→细(月在最上,时在最底)
    rowsOut.reverse();
    while (rowsOut.length > 3) rowsOut.shift();

    return {
      start,
      endT,
      spanDays,
      pxPerDay,
      fit,
      timelineW,
      headerRows: rowsOut,
      todayX: xOfT(+today),
    };
  }, [rows, zoom, containerW]);

  // 测可视宽(含窗口缩放重算)
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const measure = () => setContainerW(el.clientWidth);
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [rows]);

  const saveView = useCallback(() => {
    if (scrollRef.current)
      sessionStorage.setItem(
        "gantt.view",
        JSON.stringify({
          zoom,
          center,
          scroll: scrollRef.current.scrollLeft,
          scope,
          scopeIds,
        }),
      );
  }, [zoom, center, scope, scopeIds]);

  // 滚动位置持久化;范围/缩放/视点变化也落盘(不滚也不丢工况,YPJ-220)
  useEffect(() => {
    saveView();
  }, [saveView]);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (savedView.scroll) el.scrollLeft = savedView.scroll;
    el.addEventListener("scroll", saveView);
    return () => el.removeEventListener("scroll", saveView);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveView, rows]);

  // 缩放:锚点(anchorClientX,视口坐标)下的数据时刻保持不动;null 锚 = 视口中心
  const zoomTo = (newPpd: number, anchorClientX?: number) => {
    const el = scrollRef.current;
    if (!el) return;
    const anchorX = (anchorClientX ?? el.clientWidth / 2 + TITLE_W) - TITLE_W;
    const dataDays = (el.scrollLeft + anchorX) / axis.pxPerDay;
    const clamped = Math.min(Math.max(newPpd, axis.fit), MAX_PPD);
    el.scrollLeft = Math.max(0, dataDays * clamped - anchorX);
    setZoom(clamped);
    setTimeout(saveView, 0);
  };

  // Ctrl+滚轮缩放(非被动监听以 preventDefault)
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      zoomTo(
        axis.pxPerDay * (e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP),
        e.clientX - rect.left,
      );
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [axis.pxPerDay, axis.fit, saveView]);

  // 视点日变化时滚动到视点日居中(缩放导致的 pxPerDay 变化不重触发,避免覆盖锚点滚动)
  const lastCenterRef = useRef("");
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !center || center === lastCenterRef.current) return;
    lastCenterRef.current = center;
    const x = ((+parseLocal(center) - +axis.start) / DAY) * axis.pxPerDay;
    el.scrollLeft = Math.max(0, x - (el.clientWidth - TITLE_W) / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [center, axis.pxPerDay, rows]);

  const fmtDay = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const shiftCenter = (days: number) => {
    const base = center ? parseLocal(center) : new Date();
    base.setDate(base.getDate() + days);
    setCenter(fmtDay(base));
  };
  // 日窗预设 / 今日:视点日为中心,N 天撑满可视区
  const goToDayWindow = (days: number | null) => {
    const target =
      days === null ? new Date() : center ? parseLocal(center) : new Date();
    lastCenterRef.current = ""; // 同值重设也要触发居中
    setCenter(fmtDay(target));
    if (days !== null && containerW > TITLE_W)
      setZoom((containerW - TITLE_W) / days);
    else if (days === null) setZoom(null);
  };

  const xOf = (date: string | null) =>
    date === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            ((+parseLocal(date) - +axis.start) / DAY) * axis.pxPerDay,
          ),
        );

  // YPJ-218(spec 0139)B3:条宽含当天——仅 date-only +1 天;datetime 右端=精确 due
  const xEndOf = (due: string | null) =>
    due === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            ((+parseLocal(due) +
              (/^\d{4}-\d{2}-\d{2}$/.test(due) ? DAY : 0) -
              +axis.start) /
              DAY) *
              axis.pxPerDay,
          ),
        );

  // YPJ-218(spec 0139):父汇总条 = 全部子孙排期区间的并集;父自有日期忽略不画
  function summaryOf(itemId: string): [string, string] | null {
    let min: number | null = null;
    let max: number | null = null;
    const visit = (id: string) => {
      for (const x of rows ?? []) {
        if (x.parentItemId !== id) continue;
        const s = x.startDate ?? x.dueDate;
        const e = x.dueDate ?? x.startDate;
        if (s && +parseLocal(s) < (min ?? Infinity)) min = +parseLocal(s);
        if (e && +parseLocal(e) > (max ?? -Infinity)) max = +parseLocal(e);
        visit(x.itemId);
      }
    };
    visit(itemId);
    return min === null || max === null
      ? null
      : [
          new Date(min).toISOString().slice(0, 10),
          new Date(max).toISOString().slice(0, 10),
        ];
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
  // 有子孙的父归入已排期(靠汇总条画);真未排期 = 无日期且无子
  const hasKid = (r: GanttRow) =>
    (rows ?? []).some((x) => x.parentItemId === r.itemId);
  const unscheduled = visible.filter(
    (r) => !r.startDate && !r.dueDate && !hasKid(r),
  );
  const scheduled = visible.filter(
    (r) => r.startDate || r.dueDate || hasKid(r),
  );

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
          {/* 全览:回到数据全区间(fit) */}
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
          {/* 今日回中 */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-today"
            onClick={() => goToDayWindow(null)}
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

          {/* 时间轴视口:横滚;标题列 sticky;内容宽 = 280 + 跨度×px/天 */}
          <Box
            ref={scrollRef}
            data-testid="gantt-scroll"
            sx={{
              position: "relative",
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 1,
              overflowX: "auto",
              overflowY: "hidden",
            }}
          >
            <Box sx={{ width: TITLE_W + axis.timelineW, minWidth: "100%" }}>
              {/* header 自适应 2–3 行(月/周/日/时,自上而下粗→细) */}
              {axis.headerRows.map((row) => (
                <Stack
                  key={row.gran}
                  direction="row"
                  data-testid={`gantt-row-${row.gran}`}
                  sx={{
                    borderBottom: "1px solid",
                    borderColor: "divider",
                    bgcolor: "background.default",
                  }}
                >
                  <Box
                    sx={{
                      width: TITLE_W,
                      flexShrink: 0,
                      position: "sticky",
                      left: 0,
                      zIndex: 3,
                      bgcolor: "background.default",
                    }}
                  />
                  <Box
                    sx={{
                      position: "relative",
                      width: axis.timelineW,
                      flexShrink: 0,
                      height: 24,
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
                </Stack>
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

              {scheduled.map((r) => {
                const isFinal = r.statusType === "FINAL";
                const summary = hasKid(r) ? summaryOf(r.itemId) : null;
                const summaryLeft = summary ? (xOf(summary[0]) ?? 0) : 0;
                const summaryWidth =
                  summary !== null
                    ? Math.max((xEndOf(summary[1]) ?? 0) - summaryLeft, 1.2)
                    : 0;
                // 有子孙的父行只画汇总条,不画自有条
                const drawOwn = !hasKid(r);
                const hasStart = drawOwn && r.startDate !== null;
                const left = xOf(hasStart ? r.startDate : r.dueDate) ?? 0;
                // due 为空不外延到轴末端(S1 遗留,YPJ-220 反馈):画起点小标记;start+estimate 推条长另票
                const right =
                  r.dueDate !== null
                    ? (xEndOf(r.dueDate) ?? axis.timelineW)
                    : left;
                const width = Math.max(hasStart ? right - left : 1.2, 1.2);
                const folded = collapsed.has(r.itemId);
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
                    {/* 行标题(左侧 sticky 固定宽) */}
                    <Box
                      sx={{
                        width: TITLE_W,
                        flexShrink: 0,
                        position: "sticky",
                        left: 0,
                        zIndex: 2,
                        bgcolor: "background.default",
                        pl: 1 + r.depth * 2,
                        py: 0.5,
                        "&:hover": { bgcolor: "background.default" },
                      }}
                    >
                      <Stack direction="row" spacing={0.5} alignItems="center">
                        {hasKid(r) && (
                          <Button
                            size="small"
                            onClick={() => toggle(r.itemId)}
                            sx={{ minWidth: 0, px: 0.5 }}
                          >
                            {folded ? "▶" : "▼"}
                          </Button>
                        )}
                        <Chip
                          label={r.number}
                          size="small"
                          variant="outlined"
                          sx={{ height: 20 }}
                        />
                        {/* 标题可点进详情(条形区另有整块覆盖链接;YPJ-220 反馈补) */}
                        <Link
                          href={`/p/${key}/i/${r.itemId}?from=gantt`}
                          style={{ minWidth: 0 }}
                        >
                          <Typography
                            variant="caption"
                            noWrap
                            sx={{
                              maxWidth: 120,
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

                    {/* 条区(固定像素宽) */}
                    <Box
                      sx={{
                        position: "relative",
                        width: axis.timelineW,
                        flexShrink: 0,
                        height: 44,
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
                            bgcolor: "text.secondary",
                            opacity: 0.45,
                          }}
                        />
                      </Box>
                      <Box
                        data-final={isFinal ? "" : undefined}
                        data-testid={`gantt-bar-${r.number}`}
                        sx={{
                          position: "absolute",
                          left,
                          width,
                          top: 10,
                          display: drawOwn ? "block" : "none",
                        }}
                      >
                        {hasStart ? (
                          <Box
                            sx={{
                              height: 14,
                              borderRadius: 1,
                              bgcolor: isFinal
                                ? "text.disabled"
                                : r.overdue
                                  ? "error.main"
                                  : "primary.main",
                              opacity: isFinal ? 0.5 : 0.85,
                            }}
                          />
                        ) : (
                          <Box
                            sx={{
                              width: 12,
                              height: 12,
                              transform: "rotate(45deg)",
                              bgcolor: isFinal
                                ? "text.disabled"
                                : r.overdue
                                  ? "error.main"
                                  : "warning.main",
                              opacity: isFinal ? 0.5 : 1,
                              mx: "auto",
                            }}
                          />
                        )}
                      </Box>
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
                        {" "}
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
              })}
            </Box>
          </Box>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ mt: 1, display: "block" }}
          >
            蓝竖线 = 今天 · 菱形 = 里程碑(只有截止日期) · 红色 = 超期 · 灰 =
            已完成 · 灰横条 = 父汇总(子孙并集) · Ctrl+滚轮或 ± 缩放 ·
            「今日」回中今天
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
