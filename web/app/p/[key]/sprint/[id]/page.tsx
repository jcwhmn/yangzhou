"use client";

import {
  Button,
  Chip,
  Container,
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
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { t } from "@/lib/texts";
import { SignalChip, type Signal } from "@/components/Verdict";

type Sprint = { sprintId: string; name: string; status: string; startDate: string | null; endDate: string | null };
type Item = {
  itemId: string;
  number: string;
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
 * 头部 = 名称/日期/状态切换(planned→active→completed);看板(只读列视图,状态迁移去主看板或详情页)/ 表格;
 * 空 sprint 给出指派路径提示;未知 sprint id → 页内错误态 + 返回上一页(用户反馈 2026-09-29)。
 * 完成的 sprint 即历史回看:items 为完成时仍在其列的成员关系。
 */
export default function SprintPage() {
  const { key, id } = useParams<{ key: string; id: string }>();
  const router = useRouter();
  const [sprint, setSprint] = useState<Sprint | null>(null);
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [view, setView] = useState("board");
  const [error, setError] = useState("");
  const [gone, setGone] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [allSprints, setAllSprints] = useState<Sprint[]>([]);
  const [confirmFlow, setConfirmFlow] = useState<null | "start" | "complete">(null);

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
      <Container maxWidth="sm" sx={{ py: 10 }}>
        <Stack spacing={2} alignItems="center">
          <Typography variant="h5">Sprint 不存在或已被删除</Typography>
          <Button variant="contained" onClick={() => router.back()}>
            ← 返回上一页
          </Button>
        </Stack>
      </Container>
    );
  }

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
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
            <ToggleButtonGroup size="small" value={view} exclusive onChange={(_, v) => v && setView(v)}>
              <ToggleButton value="board">看板</ToggleButton>
              <ToggleButton value="table">表格</ToggleButton>
            </ToggleButtonGroup>
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

          {items !== null && items.length > 0 && view === "board" && (
            <Stack direction="row" spacing={2} sx={{ overflowX: "auto", pb: 2 }}>
              {statuses.map((s) => {
                const list = items.filter((it) => it.status === s.name);
                return (
                  <Stack key={s.statusId} spacing={1} sx={{ minWidth: 260, flexShrink: 0 }}>
                    <Typography variant="subtitle2">
                      {s.name}({list.length})
                      {s.isFinal && " [终态]"}
                    </Typography>
                    {list.length === 0 && (
                      <Typography variant="caption" color="text.secondary">
                        (空)
                      </Typography>
                    )}
                    {list.map((it) => (
                      <Link key={it.itemId} href={`/p/${key}/i/${it.itemId}`} style={{ textDecoration: "none", color: "inherit" }}>
                        <Stack
                          sx={{
                            p: 1,
                            borderRadius: 2,
                            bgcolor: "background.paper",
                            "&:hover": { boxShadow: 6 },
                            cursor: "pointer",
                          }}
                        >
                          <Stack direction="row" spacing={1} alignItems="center">
                            <Chip label={it.number} size="small" variant="outlined" />
                            {it.priority && <Chip size="small" label={it.priority} variant="outlined" />}
                            {it.assignee && (
                              <Typography variant="caption" color="text.secondary">
                                👤 {it.assignee}
                              </Typography>
                            )}
                          </Stack>
                          <Typography variant="body2" sx={{ mt: 0.5, wordBreak: "break-word" }}>
                            {it.title}
                          </Typography>
                        </Stack>
                      </Link>
                    ))}
                  </Stack>
                );
              })}
            </Stack>
          )}

          {items !== null && items.length > 0 && view === "table" && (
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
                        <Link href={`/p/${key}/i/${it.itemId}`}>
                          <Typography variant="body2">{it.number}</Typography>
                        </Link>
                      </TableCell>
                      <TableCell>
                        <Link href={`/p/${key}/i/${it.itemId}`}>
                          <Typography variant="body2">{it.title}</Typography>
                        </Link>
                      </TableCell>
                      <TableCell>{it.priority ?? "—"}</TableCell>
                      <TableCell>{it.status}</TableCell>
                      <TableCell>{it.assignee ?? "—"}</TableCell>
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
    </Container>
  );
}
