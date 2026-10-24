"use client";

import {
  Button,
  Chip,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItem,
  ListItemText,
  Stack,
  Table,
  ToggleButton,
  ToggleButtonGroup,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import { BarChart } from "@mui/x-charts/BarChart";
import { LineChart } from "@mui/x-charts/LineChart";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { t } from "@/lib/texts";
import { AssignPopover } from "@/components/AssignPopover";
import { SignalChip, type Signal } from "@/components/Verdict";

type Sprint = { sprintId: string; name: string; status: string; startDate: string | null; endDate: string | null };
type Item = {
  itemId: string;
  number: string;
  parentNumber?: string | null;
  title: string;
  status: string;
  assignee: string | null;
  priority: string | null;
  dueDate: string | null;
  feasSignal: Signal | null;
};
type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Candidate = { itemId: string; number: string; title: string; status: string; assignee: string | null };

/**
 * V12-S3 Sprint 落地页(spec 0010,grill 裁决 1/2):
 * 头部 = 名称/日期/状态切换(planned→active→completed)/ 表格(V19 YPJ-41 移除看板视图,看板语义归项目级看板,spec 0023/0034);
 * 空 sprint 给出指派路径提示;未知 sprint id → 页内错误态 + 返回上一页(用户反馈 2026-09-29)。
 * 完成的 sprint 即历史回看:items 为完成时仍在其列的成员关系。
 */
export default function SprintPage() {
  const { key, id } = useParams<{ key: string; id: string }>();
  const router = useRouter();
  const [sprint, setSprint] = useState<Sprint | null>(null);
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [error, setError] = useState("");
  const [gone, setGone] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [allSprints, setAllSprints] = useState<Sprint[]>([]);
  const [confirmFlow, setConfirmFlow] = useState<null | "start" | "complete">(null);
  // YPJ-73:图表视图切换(燃専数据不足时显示空态而非整块消失)
  const [chartView, setChartView] = useState<"burndown" | "byStatus" | "byAssignee">("burndown");
  // YPJ-46:burndown(剩余 vs 理想线);端点要求 sprint 有起止日期
  const [bd, setBd] = useState<{ ideal: { date: string; remaining: number }[]; actual: { date: string; remaining: number }[] } | null>(null);

  useEffect(() => {
    Promise.all([
      api<Sprint>(`/api/projects/${key}/sprints/${id}`),
      api<Item[]>(`/api/projects/${key}/sprints/${id}/items`),
      api<{ statuses: Status[] }>(`/api/projects/${key}`),
      api<Sprint[]>(`/api/projects/${key}/sprints`),
    ])
      .then(([s, its, p, all]) => {
        setSprint(s);
        setItems(its);
        setStatuses(p.statuses);
        setAllSprints(all);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setGone(true);
        else setError(e instanceof Error ? e.message : "加载失败");
      });
  }, [key, id]);

  async function changeStatus(status: string) {
    if (!sprint) return;
    try {
      const s = await api<Sprint>(`/api/projects/${key}/sprints/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setSprint(s);
      // YPJ-74:通知 sidebar 重拉 sprints(状态/排序即时同步)
      window.dispatchEvent(new Event("sprints-changed"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  // V13-S4:状态单向——开始/完成各一步;开始时若已有进行中 sprint 则提示(不禁止并存)
  function startSprint() {
    if (allSprints.some((s) => s.status === "active" && s.sprintId !== id)) setConfirmFlow("start");
    else void doStart();
  }
  async function doStart() {
    await changeStatus("active");
    setConfirmFlow(null);
  }
  function completeSprint() {
    setConfirmFlow("complete");
  }
  async function doComplete() {
    await changeStatus("completed");
    setConfirmFlow(null);
  }

  useEffect(() => {
    let alive = true;
    api<{ ideal: { date: string; remaining: number }[]; actual: { date: string; remaining: number }[] }>(
      `/api/projects/${key}/metrics/burndown?groupId=${id}`,
    )
      .then((r) => alive && setBd({ ideal: r.ideal, actual: r.actual }))
      .catch(() => alive && setBd(null));
    return () => {
      alive = false;
    };
  }, [key, id]);

  // V13-S3:拉入/移出同一对话框——列出非终态 item 多选,确认后逐条走既有 PUT /items/{id}/sprint
  async function openAdd() {
    try {
      // V13-S4:范围 = Backlog + 本 sprint 成员(其它 sprint 的 item 不列,挪动走详情下拉)
      const backlogItems = await api<Candidate[]>(`/api/projects/${key}/backlog`);
      const members = (items ?? []).map((i) => ({ itemId: i.itemId, number: i.number, title: i.title, status: i.status, assignee: i.assignee }));
      const memberIds = new Set(members.map((m) => m.itemId));
      setCandidates([...members, ...backlogItems.filter((b) => !memberIds.has(b.itemId))]);
      setSelected(new Set(members.map((m) => m.itemId)));
      setAddOpen(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }

  async function applyMembership() {
    setBusy(true);
    try {
      const prev = new Set((items ?? []).map((i) => i.itemId));
      for (const c of candidates) {
        const now = selected.has(c.itemId);
        if (prev.has(c.itemId) === now) continue;
        await api(`/api/items/${c.itemId}/sprint`, { method: "PUT", body: JSON.stringify({ sprintId: now ? id : null }) });
      }
      setAddOpen(false);
      setItems(await api<Item[]>(`/api/projects/${key}/sprints/${id}/items`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    } finally {
      setBusy(false);
    }
  }

  // 页内错误态:未知 sprint id 等;给返回按钮,不依赖浏览器后退(用户反馈)
  if (gone) {
    return (
      <PageShell>
        <Stack spacing={2} alignItems="center">
          <Typography variant="h5">Sprint 不存在或已被删除</Typography>
          <Button variant="contained" onClick={() => router.back()}>
            ← 返回上一页
          </Button>
        </Stack>
      </PageShell>
    );
  }

  return (
    <PageShell>
      <Button size="small" onClick={() => router.back()} sx={{ mb: 1 }}>
        ← 返回
      </Button>
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}
      {!sprint && !gone && <Typography color="text.secondary">加载中…</Typography>}
      {sprint && (
        <>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
            <Typography variant="h5">{sprint.name}</Typography>
            <Chip
              size="small"
              label={t.projectNav.sprintStatus[sprint.status as keyof typeof t.projectNav.sprintStatus] ?? sprint.status}
              color={sprint.status === "active" ? "primary" : sprint.status === "completed" ? "default" : "warning"}
            />
            <Typography color="text.secondary" variant="body2">
              {sprint.startDate ?? "—"} ~ {sprint.endDate ?? "—"}
            </Typography>
            {/* V14 组级 rollup:组内 items feasSignal 取最差(红>黄>绿;无信号不显示) */}
            {(() => {
              if (!items || items.length === 0) return null;
              const rank: Record<Signal, number> = { RED: 0, YELLOW: 1, GREEN: 2 };
              const sigs = items.map((i) => i.feasSignal).filter((s): s is Signal => s !== null);
              return sigs.length > 0 ? <SignalChip signal={sigs.reduce((a, b) => (rank[a] <= rank[b] ? a : b))} size="small" /> : null;
            })()}
            <Stack direction="row" spacing={1} alignItems="center" sx={{ ml: "auto" }}>
              {sprint.status === "planned" && (
                <Button size="small" variant="contained" onClick={startSprint}>
                  ▶ 开始 Sprint
                </Button>
              )}
              {sprint.status === "active" && (
                <Button size="small" variant="contained" onClick={completeSprint}>
                  ✓ 完成 Sprint
                </Button>
              )}
            </Stack>
            {sprint.status !== "completed" && (
              <Button size="small" variant="outlined" onClick={openAdd}>
                拉入 item
              </Button>
            )}
          </Stack>
          {sprint.status === "completed" && (
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 2 }}>
              此 Sprint 已完成,以下为历史 items(成员关系不可再变)。
            </Typography>
          )}

          {items !== null && items.length === 0 && (
            <Typography color="text.secondary" sx={{ mt: 4 }}>
              此 Sprint 还没有 item——点右上「拉入 item」批量勾选,或在 item 详情用「Sprint」下拉指派。
            </Typography>
          )}

          <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1 }}>
            <ToggleButtonGroup
              size="small"
              exclusive
              value={chartView}
              onChange={(_, v) => v && setChartView(v)}
            >
              <ToggleButton value="burndown">{t.sprintCharts.burndown}</ToggleButton>
              <ToggleButton value="byStatus">{t.sprintCharts.byStatus}</ToggleButton>
              <ToggleButton value="byAssignee">{t.sprintCharts.byAssignee}</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {chartView === "burndown" &&
            (bd && bd.actual.length > 1 ? (
              <LineChart
                height={260}
                xAxis={[{ scaleType: "time", data: bd.actual.map((p) => new Date(p.date)) }]}
                series={[
                  { label: t.flow.remaining, data: bd.actual.map((p) => p.remaining), showMark: false },
                  { label: t.flow.ideal, data: bd.ideal.map((p) => p.remaining), showMark: false },
                ]}
                sx={{ mb: 2 }}
              />
            ) : (
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 2 }}>
                {t.sprintCharts.burndownEmpty}
              </Typography>
            ))}
          {chartView !== "burndown" && items !== null && items.length > 0 && (
            <ChartBy
              view={chartView}
              items={items}
              statusOrder={statuses.map((s) => s.name)}
              sx={{ mb: 2 }}
            />
          )}

          {items !== null && items.length > 0 && (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {["编号", "标题", "优先级", "状态", "负责人", "截止"].map((c) => (
                      <TableCell key={c}>{c}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((it) => (
                    <TableRow key={it.itemId} hover>
                      <TableCell>
                        <Link href={`/p/${key}/i/${it.itemId}?from=sprint-${id}`}>
                          <Typography variant="body2" sx={{ whiteSpace: "nowrap" }}>{(it.parentNumber ? it.parentNumber + " › " : "") + it.number}</Typography>
                        </Link>
                      </TableCell>
                      <TableCell>
                        <Link href={`/p/${key}/i/${it.itemId}?from=sprint-${id}`}>
                          <Typography variant="body2">{it.title}</Typography>
                        </Link>
                      </TableCell>
                      <TableCell>{it.priority ?? "—"}</TableCell>
                      <TableCell>{it.status}</TableCell>
                      <TableCell>
                        <AssignPopover
                          itemId={it.itemId}
                          assignee={it.assignee}
                          onChanged={() =>
                            api<Item[]>(`/api/projects/${key}/sprints/${id}/items`).then(setItems).catch(() => {})
                          }
                        >
                          {(onClick) => (
                            <Chip
                              size="small"
                              variant="outlined"
                              label={it.assignee ? `👤 ${it.assignee}` : t.assign.none}
                              onClick={onClick}
                              sx={it.assignee ? undefined : { opacity: 0.55 }}
                            />
                          )}
                        </AssignPopover>
                      </TableCell>
                      <TableCell>{it.dueDate ?? "—"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <Dialog open={addOpen} onClose={() => setAddOpen(false)} maxWidth="sm" fullWidth>
            <DialogTitle>拉入 item</DialogTitle>
            <DialogContent dividers sx={{ maxHeight: 480 }}>
              <List dense disablePadding>
                {candidates.map((it) => (
                  <ListItem key={it.itemId} dense disableGutters>
                    <Checkbox
                      size="small"
                      checked={selected.has(it.itemId)}
                      onChange={() =>
                        setSelected((prev) => {
                          const next = new Set(prev);
                          if (next.has(it.itemId)) next.delete(it.itemId);
                          else next.add(it.itemId);
                          return next;
                        })
                      }
                    />
                    <ListItemText
                      primary={`${it.number} · ${it.title}`}
                      secondary={[it.status, it.assignee ? `👤 ${it.assignee}` : null].filter(Boolean).join(" · ")}
                    />
                  </ListItem>
                ))}
                {candidates.length === 0 && (
                  <Typography variant="caption" color="text.secondary">
                    项目里没有可拉入的非终态 item。
                  </Typography>
                )}
              </List>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setAddOpen(false)}>取消</Button>
              <Button variant="contained" onClick={applyMembership} disabled={busy}>
                完成
              </Button>
            </DialogActions>
          </Dialog>
          <Dialog open={confirmFlow !== null} onClose={() => setConfirmFlow(null)} maxWidth="xs" fullWidth>
            <DialogTitle>{confirmFlow === "start" ? "开始 Sprint" : "完成 Sprint"}</DialogTitle>
            <DialogContent>
              <Typography>
                {confirmFlow === "start"
                  ? `项目已有进行中的 Sprint「${allSprints.find((s) => s.status === "active" && s.sprintId !== id)?.name ?? ""}」,允许同时存在多个进行中。仍要开始本 Sprint?`
                  : `未完成的 ${(items ?? []).filter((it) => !statuses.find((st) => st.name === it.status)?.isFinal).length} 个 item 将回到 Backlog 供重新规划;已完成 item 保留在本页历史。`}
              </Typography>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setConfirmFlow(null)}>取消</Button>
              <Button variant="contained" onClick={confirmFlow === "start" ? doStart : doComplete}>
                {confirmFlow === "start" ? "仍要开始" : "确认完成"}
              </Button>
            </DialogActions>
          </Dialog>
        </>
      )}
    </PageShell>
  );
}

// YPJ-73:sprint 页图表——按状态/按负责人的 item 计数(横向条形,数据全来自页内 items)
function ChartBy({
  view,
  items,
  statusOrder,
  ...rest
}: {
  view: "byStatus" | "byAssignee";
  items: Item[];
  statusOrder: string[];
} & { sx?: object }) {
  const rows = useMemo(() => {
    const counts = new Map<string, number>();
    for (const i of items) {
      const k = view === "byStatus" ? i.status : (i.assignee ?? t.sprintCharts.unassigned);
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const order =
      view === "byStatus"
        ? [...statusOrder, ...counts.keys()].filter((k, idx, a) => counts.has(k) && a.indexOf(k) === idx)
        : [...counts.keys()].sort();
    return order.map((name) => ({ name, count: counts.get(name)! }));
  }, [view, items, statusOrder]);
  return (
    <BarChart
      height={260}
      layout="horizontal"
      yAxis={[{ scaleType: "band", data: rows.map((r) => r.name) }]}
      series={[{ label: t.sprintCharts.count, data: rows.map((r) => r.count) }]}
      {...rest}
    />
  );
}
