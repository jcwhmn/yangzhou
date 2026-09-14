"use client";

import { Box, Button, Chip, Container, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type GanttRow = {
  itemId: string;
  parentItemId: string | null;
  number: string;
  title: string;
  startDate: string | null;
  dueDate: string | null;
  statusName: string;
  overdue: boolean;
  depth: number;
};

const DAY = 24 * 60 * 60 * 1000;

/** V8-S2:单项目树形甘特(只读)。条 = start→due;只有 due 画里程碑菱形;今日线;未排期折叠组。 */
export default function GanttPage() {
  const { key } = useParams<{ key: string }>();
  const [rows, setRows] = useState<GanttRow[] | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [showUnscheduled, setShowUnscheduled] = useState(false);

  const load = useCallback(async () => {
    setRows(await api<GanttRow[]>(`/api/projects/${key}/gantt`));
  }, [key]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 时间轴:取所有日期的 min/max,前后各留 3 天;全空则今天~+30 天
  const axis = useMemo(() => {
    const dates = (rows ?? []).flatMap((r) => [r.startDate, r.dueDate].filter(Boolean) as string[]);
    const today = new Date();
    const min = dates.length ? new Date(Math.min(...dates.map((d) => +new Date(d)))) : today;
    const max = dates.length ? new Date(Math.max(...dates.map((d) => +new Date(d)))) : new Date(+today + 30 * DAY);
    const start = new Date(+min - 3 * DAY);
    const end = new Date(+max + 4 * DAY);
    const span = Math.max(+end - +start, DAY);
    const ticks: string[] = [];
    for (let i = 0; i <= 8; i++) {
      ticks.push(new Date(+start + (span * i) / 8).toISOString().slice(0, 10));
    }
    return { start, span, ticks, todayX: ((+today - +start) / span) * 100 };
  }, [rows]);

  const xOf = (date: string | null) =>
    date === null ? null : Math.min(100, Math.max(0, ((+new Date(date) - +axis.start) / axis.span) * 100));

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
  const unscheduled = visible.filter((r) => !r.startDate && !r.dueDate);
  const scheduled = visible.filter((r) => r.startDate || r.dueDate);
  const byId = new Map((rows ?? []).map((r) => [r.itemId, r]));

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <AppNav />
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{`${String(key)} · 甘特`}</Typography>
        <Button size="small" component={Link} href={`/p/${key}`}>
          ← 看板
        </Button>
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
              <Button size="small" variant="outlined" onClick={() => setShowUnscheduled((v) => !v)}>
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
          <Box sx={{ position: "relative", border: "1px solid", borderColor: "divider", borderRadius: 1, overflow: "hidden" }}>
            {/* 日期刻度 */}
            <Stack direction="row" sx={{ borderBottom: "1px solid", borderColor: "divider", bgcolor: "background.default" }}>
              {axis.ticks.map((d) => (
                <Typography key={d} variant="caption" color="text.secondary" sx={{ flex: 1, px: 0.5 }}>
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
              const hasStart = r.startDate !== null;
              const left = xOf(hasStart ? r.startDate : r.dueDate) ?? 0;
              const right = xOf(r.dueDate) ?? 100;
              const width = Math.max(hasStart ? right - left : 1.2, 1.2);
              const hasKids = (rows ?? []).some((x) => x.parentItemId === r.itemId);
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
                  <Box sx={{ width: 280, flexShrink: 0, pl: 1 + r.depth * 2, py: 0.5 }}>
                    <Stack direction="row" spacing={0.5} alignItems="center">
                      {hasKids && (
                        <Button size="small" onClick={() => toggle(r.itemId)} sx={{ minWidth: 0, px: 0.5 }}>
                          {folded ? "▶" : "▼"}
                        </Button>
                      )}
                      <Chip label={r.number} size="small" variant="outlined" sx={{ height: 20 }} />
                      <Typography variant="caption" noWrap sx={{ maxWidth: 150 }}>
                        {r.title}
                      </Typography>
                    </Stack>
                  </Box>

                  {/* 条区(跳过左侧标题宽度的相对定位) */}
                  <Box sx={{ position: "relative", flex: 1, height: 44 }}>
                    {axis.todayX >= 0 && axis.todayX <= 100 && (
                      <Box sx={{ position: "absolute", left: `${axis.todayX}%`, top: 0, bottom: 0, width: "2px", bgcolor: "info.main", opacity: 0.5 }} />
                    )}
                    <Box sx={{ position: "absolute", left: `${left}%`, width: `${width}%`, top: 10 }}>
                      {hasStart ? (
                        <Box
                          sx={{
                            height: 14,
                            borderRadius: 1,
                            bgcolor: r.overdue ? "error.main" : "primary.main",
                            opacity: 0.85,
                          }}
                        />
                      ) : (
                        <Box
                          sx={{
                            width: 12,
                            height: 12,
                            transform: "rotate(45deg)",
                            bgcolor: r.overdue ? "error.main" : "warning.main",
                            mx: "auto",
                          }}
                        />
                      )}
                    </Box>
                    <Link
                      href={`/p/${key}/i/${r.itemId}`}
                      style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0, display: "block" }}
                      aria-label={r.number}
                    >
                      {" "}
                    </Link>
                  </Box>

                  <Chip label={r.statusName} size="small" variant="outlined" sx={{ mr: 1, height: 20 }} />
                </Box>
              );
            })}
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "block" }}>
            蓝竖线 = 今天 · 菱形 = 里程碑(只有截止日期) · 红色 = 超期 · 点行进详情
          </Typography>
        </>
      )}
    </Container>
  );

  function RowTitle({ r }: { r: GanttRow }) {
    return (
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip label={r.number} size="small" variant="outlined" sx={{ height: 20 }} />
        <Typography variant="caption">{r.title}</Typography>
      </Stack>
    );
  }
}
