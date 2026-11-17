"use client";

import { Box, Button, Chip, Stack, Typography } from "@mui/material";
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

// YPJ-219(spec 0140):六级缩放,px/天;时档=单日 0–24 墙钟全程(不跳周末;工作制轴归 YPJ-226)
type Granularity = "year" | "quarter" | "month" | "week" | "day" | "hour";
const GRANULARITIES: [Granularity, string, number][] = [
  ["year", "年", 2],
  ["quarter", "季", 6],
  ["month", "月", 15],
  ["week", "周", 40],
  ["day", "日", 120],
  ["hour", "时", 1200],
];
const TITLE_W = 280;
const MIN_TIMELINE_W = 900;
const TICK_MIN_PX = 48; // 刻度标签最小间距(密度不足时抽稀/放粗步长)

// estimateUnit 封闭枚举(hour/day/week)的短标(YPJ-204)
const EST_UNIT_SHORT: Record<string, string> = {
  hour: "h",
  day: "d",
  week: "w",
};

/** V8-S2:单项目树形甘特(只读)。条 = start→due;只有 due 画里程碑菱形;今日线;未排期折叠组。 */
export default function GanttPage() {
  const { key } = useParams<{ key: string }>();
  const [rows, setRows] = useState<GanttRow[] | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [showUnscheduled, setShowUnscheduled] = useState(false);
  const [granularity, setGranularity] = useState<Granularity>("month");
  // YPJ-219(spec 0140 拍板补充):时档基准日可导航(默认今日)
  const [hourBase, setHourBase] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    const data = await api<{ rows: GanttRow[] }>(`/api/projects/${key}/gantt`);
    setRows(data.rows);
  }, [key]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 时间轴(YPJ-219 spec 0140):像素制——跨度天数 × pxPerDay = 内容宽;时档固定基准日一天
  const axis = useMemo(() => {
    const today = new Date();
    const pxPerDay = GRANULARITIES.find(([g]) => g === granularity)?.[2] ?? 15;
    let start: Date;
    if (granularity === "hour") {
      // 单日 0–24 全程轴,基准=基准日(默认今日)
      start = hourBase
        ? parseLocal(hourBase)
        : new Date(today.getFullYear(), today.getMonth(), today.getDate());
    } else {
      const dates = (rows ?? []).flatMap(
        (r) => [r.startDate, r.dueDate].filter(Boolean) as string[],
      );
      const min = dates.length
        ? new Date(Math.min(...dates.map((d) => +parseLocal(d))))
        : today;
      const max = dates.length
        ? new Date(Math.max(...dates.map((d) => +parseLocal(d))))
        : new Date(+today + 30 * DAY);
      start = new Date(+min - 3 * DAY);
    }
    const end =
      granularity === "hour"
        ? new Date(+start + DAY)
        : new Date(
            +start +
              DAY * 400 * (pxPerDay <= 6 ? 1 : pxPerDay <= 40 ? 0.4 : 0.15),
          );
    const spanDays = Math.max((+end - +start) / DAY, 1);
    const timelineW = Math.max(spanDays * pxPerDay, MIN_TIMELINE_W);

    // 刻度:按档取整点;标签按最小间距抽稀
    const ticks: { x: number; label: string }[] = [];
    const push = (d: Date, label: string) =>
      ticks.push({
        x: ((+d - +start) / DAY) * pxPerDay,
        label,
      });
    if (granularity === "hour") {
      for (let h = 0; h < 24; h++) {
        push(new Date(+start + (h * DAY) / 24), `${h}:00`);
      }
    } else {
      const cur = new Date(
        start.getFullYear(),
        start.getMonth(),
        start.getDate(),
      );
      const add = {
        year: () => cur.setFullYear(cur.getFullYear() + 1),
        quarter: () => cur.setMonth(cur.getMonth() + 3),
        month: () => cur.setMonth(cur.getMonth() + 1),
        week: () => cur.setDate(cur.getDate() + 7),
        day: () => cur.setDate(cur.getDate() + 1),
      }[granularity as Exclude<Granularity, "hour">];
      // 对齐:年/季/月对齐 1 号,周对齐周一,日从 start 起取整天
      if (granularity === "week") {
        const dow = (cur.getDay() + 6) % 7;
        cur.setDate(cur.getDate() - dow);
      }
      while (+cur <= +start + spanDays * DAY) {
        const label =
          granularity === "year"
            ? `${cur.getFullYear()}`
            : granularity === "quarter"
              ? `${cur.getFullYear()}-Q${Math.floor(cur.getMonth() / 3) + 1}`
              : granularity === "month"
                ? `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`
                : `${String(cur.getMonth() + 1).padStart(2, "0")}-${String(cur.getDate()).padStart(2, "0")}`;
        push(new Date(cur), label);
        add();
      }
    }
    // 密度不足 → 按序抽稀(保序,首刻度保留)
    const shown: { x: number; label: string }[] = [];
    for (const tk of ticks) {
      if (shown.length === 0 || tk.x - shown[shown.length - 1].x >= TICK_MIN_PX)
        shown.push(tk);
    }

    return {
      start,
      pxPerDay,
      timelineW,
      ticks: shown,
      todayX: ((+today - +start) / DAY) * pxPerDay,
    };
  }, [rows, granularity, hourBase]);

  // 切档/进页:今日线入视口(spec 0140)
  useEffect(() => {
    const el = scrollRef.current;
    if (el) {
      el.scrollLeft = Math.max(0, axis.todayX - (el.clientWidth - TITLE_W) / 2);
    }
  }, [granularity, rows, axis.todayX]);

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

  // YPJ-218(spec 0139)B3:条宽含 due 当天——右端取 due+1 天(菱形不用)
  const xEndOf = (due: string | null) =>
    due === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            ((+parseLocal(due) + DAY - +axis.start) / DAY) * axis.pxPerDay,
          ),
        );

  // YPJ-218(spec 0139):父汇总条 = 全部子孙排期区间的并集;父自有日期忽略不画(定案:父不持有自有时间线)
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
  // YPJ-218(spec 0139):有子孙的父归入已排期(靠汇总条画);真未排期 = 无日期且无子
  const hasKid = (r: GanttRow) =>
    (rows ?? []).some((x) => x.parentItemId === r.itemId);
  const unscheduled = visible.filter(
    (r) => !r.startDate && !r.dueDate && !hasKid(r),
  );
  const scheduled = visible.filter(
    (r) => r.startDate || r.dueDate || hasKid(r),
  );
  const byId = new Map((rows ?? []).map((r) => [r.itemId, r]));

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
          {GRANULARITIES.map(([g, label]) => (
            <Button
              key={g}
              data-testid={`gantt-gran-${g}`}
              size="small"
              variant={granularity === g ? "contained" : "outlined"}
              onClick={() => setGranularity(g)}
            >
              {label}
            </Button>
          ))}
          {/* 时档基准日导航(YPJ-219 拍板补充):◀ 前一天 / 后一天 ▶ / 指定日期 */}
          {granularity === "hour" && (
            <Stack
              direction="row"
              spacing={0.5}
              alignItems="center"
              data-testid="gantt-hour-nav"
            >
              <Button
                size="small"
                variant="outlined"
                data-testid="gantt-hour-prev"
                onClick={() =>
                  setHourBase((b) => {
                    const d = b ? parseLocal(b) : new Date();
                    d.setDate(d.getDate() - 1);
                    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
                  })
                }
              >
                ◀
              </Button>
              <DateInput
                ariaLabel="时轴基准日"
                size="small"
                value={hourBase}
                onChange={setHourBase}
                sx={{ width: 160 }}
              />
              <Button
                size="small"
                variant="outlined"
                data-testid="gantt-hour-next"
                onClick={() =>
                  setHourBase((b) => {
                    const d = b ? parseLocal(b) : new Date();
                    d.setDate(d.getDate() + 1);
                    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
                  })
                }
              >
                ▶
              </Button>
            </Stack>
          )}
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

          {/* 时间轴视口(YPJ-219 spec 0140):横滚;标题列 sticky;内容宽 = 280 + 跨度×px/天 */}
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
              {/* 日期刻度 */}
              <Stack
                direction="row"
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
                  {axis.ticks.map((tk) => (
                    <Typography
                      key={`${tk.x}-${tk.label}`}
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
                  {/* 今日线(刻度行) */}
                  {axis.todayX >= 0 && axis.todayX <= axis.timelineW && (
                    <Box
                      sx={{
                        position: "absolute",
                        left: axis.todayX,
                        top: 0,
                        bottom: 0,
                        width: "2px",
                        bgcolor: "info.main",
                        opacity: 0.6,
                      }}
                    />
                  )}
                </Box>
              </Stack>

              {/* 今日线全高遮罩参考:各自行内画(条区内) */}

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
                // YPJ-218(spec 0139):有子孙的父行只画汇总条,不画自有条
                const drawOwn = !hasKid(r);
                const hasStart = drawOwn && r.startDate !== null;
                const left = xOf(hasStart ? r.startDate : r.dueDate) ?? 0;
                const right = xEndOf(r.dueDate) ?? axis.timelineW;
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
                        <Typography
                          variant="caption"
                          noWrap
                          sx={{ maxWidth: 120 }}
                        >
                          {r.title}
                        </Typography>
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
            已完成 · 灰横条 = 父汇总(子孙并集) · 点行进详情 · 横向滚动看更多
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
        <Typography variant="caption">{r.title}</Typography>
      </Stack>
    );
  }
}
