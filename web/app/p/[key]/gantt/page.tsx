"use client";

import { Box, Button, Chip, Stack, Typography } from "@mui/material";
import { PageShell } from "@/components/PageShell";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { parseLocal } from "@/lib/dates";
import { t } from "@/lib/texts";

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
type Granularity = "month" | "week" | "day";
const GRANULARITIES: [Granularity, string][] = [
  ["month", "月"],
  ["week", "周"],
  ["day", "日"],
];

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

  const load = useCallback(async () => {
    const data = await api<{ rows: GanttRow[] }>(`/api/projects/${key}/gantt`);
    setRows(data.rows);
  }, [key]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 时间轴:取所有日期的 min/max,前后各留 3 天;全空则今天~+30 天
  const axis = useMemo(() => {
    const dates = (rows ?? []).flatMap(
      (r) => [r.startDate, r.dueDate].filter(Boolean) as string[],
    );
    const today = new Date();
    const min = dates.length
      ? new Date(Math.min(...dates.map((d) => +parseLocal(d))))
      : today;
    const max = dates.length
      ? new Date(Math.max(...dates.map((d) => +parseLocal(d))))
      : new Date(+today + 30 * DAY);
    const start = new Date(+min - 3 * DAY);
    const end = new Date(+max + 4 * DAY);
    const span = Math.max(+end - +start, DAY);
    const ticks: string[] = [];
    // YPJ-204(spec 0137):粒度切换——月=每月 1 号，周=每周一，日=每日(跨度大时隔 N 个显示)
    if (granularity === "month") {
      const cur = new Date(
        Date.UTC(start.getFullYear(), start.getMonth() + 1, 1),
      );
      while (+cur <= +end) {
        ticks.push(cur.toISOString().slice(0, 10));
        cur.setUTCMonth(cur.getUTCMonth() + 1);
      }
    } else {
      const step = granularity === "week" ? 7 : 1;
      // 对齐到周一(周粒度)；日粒度直接从 start 起
      const cur = new Date(start);
      if (granularity === "week") {
        const dow = (cur.getUTCDay() + 6) % 7; // 周一=0
        cur.setUTCDate(cur.getUTCDate() + ((7 - dow) % 7));
      }
      while (+cur <= +end) {
        ticks.push(cur.toISOString().slice(0, 10));
        cur.setUTCDate(cur.getUTCDate() + step);
      }
    }
    // 刻度过密时抽稀(按容器约 900px 可用宽，每刻度至少 ~48px)
    const shown =
      ticks.length > 18
        ? ticks.filter(
            (_, i) =>
              i % Math.ceil(ticks.length / 18) === 0 || i === ticks.length - 1,
          )
        : ticks;
    return {
      start,
      span,
      ticks: shown,
      todayX: ((+today - +start) / span) * 100,
    };
  }, [rows, granularity]);

  const xOf = (date: string | null) =>
    date === null
      ? null
      : Math.min(
          100,
          Math.max(0, ((+parseLocal(date) - +axis.start) / axis.span) * 100),
        );

  // YPJ-218(spec 0139)B3:条宽含 due 当天——右端取 due+1 天(菱形不用)
  const xEndOf = (due: string | null) =>
    due === null
      ? null
      : Math.min(
          100,
          Math.max(
            0,
            ((+parseLocal(due) + DAY - +axis.start) / axis.span) * 100,
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
        <Stack direction="row" spacing={0.5} sx={{ ml: "auto" }}>
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

          {/* 时间轴 */}
          <Box
            sx={{
              position: "relative",
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 1,
              overflow: "hidden",
            }}
          >
            {/* 日期刻度 */}
            <Stack
              direction="row"
              sx={{
                borderBottom: "1px solid",
                borderColor: "divider",
                bgcolor: "background.default",
              }}
            >
              {axis.ticks.map((d) => (
                <Typography
                  key={d}
                  variant="caption"
                  color="text.secondary"
                  sx={{ flex: 1, px: 0.5 }}
                >
                  {d.slice(5)}
                </Typography>
              ))}
            </Stack>

            {/* 今日线 */}
            {axis.todayX >= 0 && axis.todayX <= 100 && (
              <Box
                sx={{
                  position: "absolute",
                  left: `${axis.todayX}%`,
                  top: 0,
                  bottom: 0,
                  width: "2px",
                  bgcolor: "info.main",
                  opacity: 0.6,
                }}
              />
            )}

            {scheduled.length === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
                暂无已排期 item——在详情页填起止日期后出现在这里。
              </Typography>
            )}

            {scheduled.map((r) => {
              const isFinal = r.statusType === "FINAL";
              const hasKids = (rows ?? []).some(
                (x) => x.parentItemId === r.itemId,
              );
              const summary = hasKids ? summaryOf(r.itemId) : null;
              const summaryLeft = summary ? (xOf(summary[0]) ?? 0) : 0;
              const summaryWidth =
                summary !== null
                  ? Math.max((xEndOf(summary[1]) ?? 0) - summaryLeft, 1.2)
                  : 0;
              // YPJ-218(spec 0139):有子孙的父行只画汇总条,不画自有条
              const drawOwn = !hasKids;
              const hasStart = drawOwn && r.startDate !== null;
              const left = xOf(hasStart ? r.startDate : r.dueDate) ?? 0;
              const right = xEndOf(r.dueDate) ?? 100;
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
                  {/* 行标题(左侧固定宽) */}
                  <Box
                    sx={{
                      width: 280,
                      flexShrink: 0,
                      pl: 1 + r.depth * 2,
                      py: 0.5,
                    }}
                  >
                    <Stack direction="row" spacing={0.5} alignItems="center">
                      {hasKids && (
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

                  {/* 条区(跳过左侧标题宽度的相对定位) */}
                  <Box sx={{ position: "relative", flex: 1, height: 44 }}>
                    {axis.todayX >= 0 && axis.todayX <= 100 && (
                      <Box
                        sx={{
                          position: "absolute",
                          left: `${axis.todayX}%`,
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
                        left: `${summaryLeft}%`,
                        width: `${summaryWidth}%`,
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
                        left: `${left}%`,
                        width: `${width}%`,
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
                    sx={{ mr: 1, height: 20 }}
                  />
                </Box>
              );
            })}
          </Box>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ mt: 1, display: "block" }}
          >
            蓝竖线 = 今天 · 菱形 = 里程碑(只有截止日期) · 红色 = 超期 · 灰 =
            已完成 · 灰横条 = 父汇总(子孙并集) · 点行进详情
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
