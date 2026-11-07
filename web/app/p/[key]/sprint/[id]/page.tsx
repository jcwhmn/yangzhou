"use client";

import {
  Box,
  Button,
  Chip,
  Checkbox,
  TextField,
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
import { useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  listSprints,
  getSprint,
  sprintAction,
  assignSprint,
  createSprint,
  level1Type,
} from "@/lib/sprints";
import { AssignPopover } from "@/components/AssignPopover";
import { SignalChip, type Signal } from "@/components/Verdict";

type Sprint = {
  sprintId: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
  parentGroupId: string | null;
};
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
type Status = {
  statusId: string;
  name: string;
  statusType: string;
  position: number;
};
// spec 0097 S3:类型 workflow(GET /group-types/{typeKey}/workflow),按钮按出边渲染
type TypeWorkflow = {
  name: string;
  statusType: string;
  transitions: { function: string; target: string }[];
};
type Candidate = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
};

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
  // 0110:版本号防乱序覆盖——初始加载的慢响应不得覆盖动作后的本地状态(单步弹出后点击更快,竞态实测)
  const sprintVer = useRef(0);
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [error, setError] = useState("");
  const [gone, setGone] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [allSprints, setAllSprints] = useState<Sprint[]>([]);
  const [wf, setWf] = useState<TypeWorkflow[]>([]);
  const [confirmFlow, setConfirmFlow] = useState<
    null | "start" | "complete" | "reopen"
  >(null);
  // 0106:组完成后的非终态成员处置(rows 默认全转入目标组,逐行可覆写回 Backlog;D2/D3)
  const [disp, setDisp] = useState<null | {
    rows: { itemId: string; title: string; toBacklog: boolean }[];
    actives: Sprint[];
    planned: Sprint[];
    l1Name: string;
    pick: string;
    newName: string;
  }>(null);
  // YPJ-73:图表视图切换(燃専数据不足时显示空态而非整块消失)
  const [chartView, setChartView] = useState<
    "burndown" | "byStatus" | "byAssignee"
  >("burndown");
  // YPJ-46:burndown(剩余 vs 理想线);端点要求 sprint 有起止日期
  const [bd, setBd] = useState<{
    ideal: { date: string; remaining: number }[];
    actual: { date: string; remaining: number }[];
  } | null>(null);

  useEffect(() => {
    const ver = ++sprintVer.current;
    Promise.all([
      getSprint(key, id),
      api<Item[]>(`/api/projects/${key}/groups/${id}/items`),
      api<{ statuses: Status[] }>(`/api/projects/${key}`),
      listSprints(key),
      api<TypeWorkflow[]>(`/api/projects/${key}/group-types/sprint/workflow`),
    ])
      .then(([s, its, p, all, w]) => {
        if (sprintVer.current === ver) setSprint(s);
        setSprint(s);
        setItems(its);
        setStatuses(p.statuses);
        setAllSprints(all);
        setWf(w);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setGone(true);
        else setError(e instanceof Error ? e.message : "加载失败");
      });
  }, [key, id]);

  async function changeStatus(status: string) {
    if (!sprint) return;
    try {
      // spec 0097 S2:状态变更走动作白名单(active=start / completed=complete)
      const s = await sprintAction(
        key,
        id,
        status === "completed" ? "complete" : "start",
      );
      sprintVer.current += 1;
      setSprint(s);
      // YPJ-74:sidebar 重拉由 sprintAction 内部广播(0105 收口)
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  // V13-S4:状态单向——开始/完成各一步;开始时若已有进行中 sprint 则提示(不禁止并存)
  function startSprint() {
    if (allSprints.some((s) => s.status === "active" && s.sprintId !== id))
      setConfirmFlow("start");
    else void doStart();
  }
  async function doStart() {
    await changeStatus("active");
    setConfirmFlow(null);
  }
  /** 0110:全 Done 完成路径(确认弹窗「确认完成」)——无处置,直迁状态。 */
  async function completeEmpty() {
    await changeStatus("completed");
    setConfirmFlow(null);
  }
  function completeSprint() {
    // 0110:有非终态成员直接弹处置(取消|进 Backlog|进「组名」);全 Done 走确认弹窗
    const nonFinal = (items ?? []).some(
      (it) =>
        statuses.find((st) => st.name === it.status)?.statusType !== "FINAL",
    );
    if (nonFinal) void doComplete();
    else setConfirmFlow("complete");
  }
  async function doComplete() {
    // 0110:完成+处置同一事务,状态迁移随处置一次性提交
    const nonFinal = (items ?? []).filter(
      (it) =>
        statuses.find((st) => st.name === it.status)?.statusType !== "FINAL",
    );
    setConfirmFlow(null); // 开处置前关确认弹窗,避免双 dialog 叠置
    const others = allSprints.filter((s) => s.sprintId !== id);
    const actives = others.filter((s) => s.status === "active");
    const planned = others.filter((s) => s.status === "planned");
    let l1Name = "Sprint";
    try {
      l1Name = (await level1Type(key))?.name ?? l1Name;
    } catch {
      /* 无类型极端:谓词天然收容 Backlog,弹窗仍可全回 Backlog */
    }
    let n = allSprints.length + 1; // 含本组:新建的是「下一号」
    while (allSprints.some((s) => s.name === `${l1Name} ${n}`)) n += 1;
    setDisp({
      rows: nonFinal.map((it) => ({
        itemId: it.itemId,
        title: it.title,
        toBacklog: false,
      })),
      actives,
      planned,
      l1Name,
      pick: (actives[0] ?? planned[0])?.sprintId ?? "",
      newName: `${l1Name} ${n}`,
    });
  }

  /** 0110:处置计划随完成单次提交——状态迁移+处置同一后端事务;失败整体回滚,弹窗保留可改计划重试。 */
  async function confirmDisposition() {
    if (!disp || busy) return;
    const { actives, planned, pick } = disp;
    setBusy(true);
    let target: string | null = null;
    try {
      if (actives.length > 0) {
        target = pick || actives[0].sprintId;
      } else if (planned.length === 1) {
        await sprintAction(key, planned[0].sprintId, "start");
        target = planned[0].sprintId;
      } else if (planned.length > 1) {
        await sprintAction(key, pick, "start");
        target = pick;
      } else {
        const name = disp.newName.trim() || `${disp.l1Name} 1`;
        const existing = allSprints.find((s) => s.name === name);
        target = (
          existing ??
          (await createSprint(key, {
            name,
            startDate: null,
            endDate: null,
            // spec 0112:处置新建组继承被完成 sprint 的父 epic(否则悬空)
            parentGroupId: sprint?.parentGroupId ?? null,
          }))
        ).sprintId;
        await sprintAction(key, target, "start");
      }
    } catch (err) {
      // 目标组未就绪 → 事务未发生,组未完成;改计划重试
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    try {
      const s = await sprintAction(key, id, "complete", {
        dispositions: disp.rows.map((r) => ({
          itemId: r.itemId,
          targetGroupId: r.toBacklog ? null : target,
        })),
      });
      sprintVer.current += 1;
      setSprint(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
    setDisp(null);
    setConfirmFlow(null);
    setBusy(false);
  }

  function targetLabel(d: typeof disp): string {
    if (!d) return "";
    if (d.actives.length > 1 || d.planned.length > 1)
      return t.sprintPage.dispPickTitle;
    if (d.actives.length === 1) return `转入进行中的「${d.actives[0].name}」`;
    if (d.planned.length === 1)
      return `将激活计划中的「${d.planned[0].name}」并转入`;
    return `将新建组「${d.newName}」并激活,转入`;
  }
  // 摘要行用:目标组名(多选时取 pick,与 confirmDisposition 落库一致)
  function targetGroupName(d: NonNullable<typeof disp>): string {
    if (d.actives.length > 0)
      return (d.actives.find((s) => s.sprintId === d.pick) ?? d.actives[0])
        .name;
    if (d.planned.length > 0)
      return (d.planned.find((s) => s.sprintId === d.pick) ?? d.planned[0])
        .name;
    return d.newName;
  }

  /** 0110:「进 Backlog」——全部非终态成员显式回池,随完成同事务落库(旧行清理由 replaceMembership 承担)。 */
  async function doBacklogAll() {
    if (!disp || busy) return;
    setBusy(true);
    try {
      const s = await sprintAction(key, id, "complete", {
        dispositions: disp.rows.map((r) => ({
          itemId: r.itemId,
          targetGroupId: null,
        })),
      });
      sprintVer.current += 1;
      setSprint(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
    setDisp(null);
    setConfirmFlow(null);
    setBusy(false);
  }

  // spec 0097 S3:按钮 = 当前状态出边(backward 仅 CLI/API 可及,UI 现状一致)
  function actionLabel(fn: string): string {
    return fn === "start"
      ? t.sprintPage.startBtn
      : fn === "complete"
        ? t.sprintPage.completeBtn
        : fn === "reopen"
          ? t.sprintPage.reopenBtn
          : fn;
  }
  function actionClick(fn: string) {
    if (fn === "start") return startSprint();
    if (fn === "complete") return completeSprint();
    // 0108:重开低显著(outlined 由确认弹窗双闸防误触)+确认制
    if (fn === "reopen") return setConfirmFlow("reopen");
  }
  async function doReopen() {
    // 0108:重开出边是 reopen,不走 changeStatus(其 active 映射 start,completed 态无此出边)
    const s = await sprintAction(key, id, "reopen");
    sprintVer.current += 1;
    setSprint(s);
    setConfirmFlow(null);
  }

  useEffect(() => {
    let alive = true;
    api<{
      ideal: { date: string; remaining: number }[];
      actual: { date: string; remaining: number }[];
    }>(`/api/projects/${key}/metrics/burndown?groupId=${id}`)
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
      const backlogItems = await api<Candidate[]>(
        `/api/projects/${key}/backlog`,
      );
      const members = (items ?? []).map((i) => ({
        itemId: i.itemId,
        number: i.number,
        title: i.title,
        status: i.status,
        assignee: i.assignee,
      }));
      const memberIds = new Set(members.map((m) => m.itemId));
      setCandidates([
        ...members,
        ...backlogItems.filter((b) => !memberIds.has(b.itemId)),
      ]);
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
        await assignSprint(key, c.itemId, now ? id : null);
      }
      setAddOpen(false);
      setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
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
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {!sprint && !gone && (
        <Typography color="text.secondary">加载中…</Typography>
      )}
      {sprint && (
        <>
          <Stack
            direction="row"
            spacing={2}
            alignItems="center"
            sx={{ mb: 1 }}
            flexWrap="wrap"
            useFlexGap
          >
            <Typography variant="h5">{sprint.name}</Typography>
            <Chip
              size="small"
              label={
                t.projectNav.sprintStatus[
                  sprint.status as keyof typeof t.projectNav.sprintStatus
                ] ?? sprint.status
              }
              color={
                sprint.status === "active"
                  ? "primary"
                  : sprint.status === "completed"
                    ? "default"
                    : "warning"
              }
            />
            <Typography color="text.secondary" variant="body2">
              {sprint.startDate ?? "—"} ~ {sprint.endDate ?? "—"}
            </Typography>
            {/* V14 组级 rollup:组内 items feasSignal 取最差(红>黄>绿;无信号不显示) */}
            {(() => {
              if (!items || items.length === 0) return null;
              const rank: Record<Signal, number> = {
                RED: 0,
                YELLOW: 1,
                GREEN: 2,
              };
              const sigs = items
                .map((i) => i.feasSignal)
                .filter((s): s is Signal => s !== null);
              return sigs.length > 0 ? (
                <SignalChip
                  signal={sigs.reduce((a, b) => (rank[a] <= rank[b] ? a : b))}
                  size="small"
                />
              ) : null;
            })()}
            <Stack
              direction="row"
              spacing={1}
              alignItems="center"
              sx={{ ml: "auto" }}
            >
              {wf
                .find((s) => s.name === sprint.status)
                ?.transitions.filter((tr) => tr.function !== "backward")
                .map((tr) => (
                  <Button
                    key={tr.function}
                    size="small"
                    variant={
                      tr.function === "reopen" ? "outlined" : "contained"
                    }
                    color={tr.function === "reopen" ? "inherit" : "primary"}
                    disabled={items === null} // 0110:items 未载禁用,防误开处置/确认弹窗
                    onClick={() => actionClick(tr.function)}
                  >
                    {actionLabel(tr.function)}
                  </Button>
                ))}
            </Stack>
            {sprint.status !== "completed" && (
              <Button size="small" variant="outlined" onClick={openAdd}>
                拉入 item
              </Button>
            )}
          </Stack>
          {sprint.status === "completed" && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: "block", mb: 2 }}
            >
              此 Sprint 已完成,以下为历史 items(成员关系不可再变,可重开组)。
            </Typography>
          )}

          {items !== null && items.length === 0 && (
            <Typography color="text.secondary" sx={{ mt: 4 }}>
              此 Sprint 还没有 item——点右上「拉入 item」批量勾选,或在 item
              详情用「Sprint」下拉指派。
            </Typography>
          )}

          <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1 }}>
            <ToggleButtonGroup
              size="small"
              exclusive
              value={chartView}
              onChange={(_, v) => v && setChartView(v)}
            >
              <ToggleButton value="burndown">
                {t.sprintCharts.burndown}
              </ToggleButton>
              <ToggleButton value="byStatus">
                {t.sprintCharts.byStatus}
              </ToggleButton>
              <ToggleButton value="byAssignee">
                {t.sprintCharts.byAssignee}
              </ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {chartView === "burndown" &&
            (bd && bd.actual.length > 1 ? (
              <LineChart
                height={260}
                xAxis={[
                  {
                    scaleType: "time",
                    data: bd.actual.map((p) => new Date(p.date)),
                  },
                ]}
                series={[
                  {
                    label: t.flow.remaining,
                    data: bd.actual.map((p) => p.remaining),
                    showMark: false,
                  },
                  {
                    label: t.flow.ideal,
                    data: bd.ideal.map((p) => p.remaining),
                    showMark: false,
                  },
                ]}
                sx={{ mb: 2 }}
              />
            ) : (
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: "block", mb: 2 }}
              >
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
                    {["编号", "标题", "优先级", "状态", "负责人", "截止"].map(
                      (c) => (
                        <TableCell key={c}>{c}</TableCell>
                      ),
                    )}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((it) => (
                    <TableRow key={it.itemId} hover>
                      <TableCell>
                        <Link
                          href={`/p/${key}/i/${it.itemId}?from=sprint-${id}`}
                        >
                          <Typography
                            variant="body2"
                            sx={{ whiteSpace: "nowrap" }}
                          >
                            {(it.parentNumber ? it.parentNumber + " › " : "") +
                              it.number}
                          </Typography>
                        </Link>
                      </TableCell>
                      <TableCell>
                        <Link
                          href={`/p/${key}/i/${it.itemId}?from=sprint-${id}`}
                        >
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
                            api<Item[]>(
                              `/api/projects/${key}/sprints/${id}/items`,
                            )
                              .then(setItems)
                              .catch(() => {})
                          }
                        >
                          {(onClick) => (
                            <Chip
                              size="small"
                              variant="outlined"
                              label={
                                it.assignee
                                  ? `👤 ${it.assignee}`
                                  : t.assign.none
                              }
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
          <Dialog
            open={addOpen}
            onClose={() => setAddOpen(false)}
            maxWidth="sm"
            fullWidth
          >
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
                      secondary={[
                        it.status,
                        it.assignee ? `👤 ${it.assignee}` : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
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
              <Button
                variant="contained"
                onClick={applyMembership}
                disabled={busy}
              >
                完成
              </Button>
            </DialogActions>
          </Dialog>
          <Dialog
            open={confirmFlow !== null}
            onClose={() => setConfirmFlow(null)}
            maxWidth="xs"
            fullWidth
          >
            <DialogTitle>
              {confirmFlow === "start"
                ? "开始 Sprint"
                : confirmFlow === "reopen"
                  ? "重开组"
                  : "完成 Sprint"}
            </DialogTitle>
            <DialogContent>
              <Typography>
                {confirmFlow === "start"
                  ? `项目已有进行中的 Sprint「${allSprints.find((s) => s.status === "active" && s.sprintId !== id)?.name ?? ""}」,允许同时存在多个进行中。仍要开始本 Sprint?`
                  : confirmFlow === "reopen"
                    ? t.sprintPage.reopenConfirm
                    : `完成后,${(items ?? []).filter((it) => !(statuses.find((st) => st.name === it.status)?.statusType === "FINAL")).length} 个未完成 item 将进入处置(选定去向);已完成 item 保留在本页历史。`}
              </Typography>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setConfirmFlow(null)}>取消</Button>
              <Button
                variant="contained"
                onClick={
                  confirmFlow === "start"
                    ? doStart
                    : confirmFlow === "reopen"
                      ? doReopen
                      : completeEmpty
                }
              >
                {confirmFlow === "start"
                  ? "仍要开始"
                  : confirmFlow === "reopen"
                    ? "确认重开"
                    : "确认完成"}
              </Button>
            </DialogActions>
          </Dialog>
          {/* 0106:非终态成员处置——目标组决策树 + 逐行覆写回 Backlog(D2/D3);0107 行内显式按钮 + 提交前摘要(方案A,消除 checkbox 误勾事故) */}
          <Dialog open={disp !== null} maxWidth="sm" fullWidth>
            <DialogTitle>
              完成「{sprint?.name ?? ""}」——{disp?.rows.length ?? 0} 个未完成
              item 待处置
            </DialogTitle>
            <DialogContent>
              <Typography sx={{ mt: 1, mb: 1, fontWeight: 600 }}>
                {targetLabel(disp)}
              </Typography>
              {disp && (disp.actives.length > 1 || disp.planned.length > 1) && (
                <Stack spacing={1} sx={{ mb: 1 }}>
                  {[...disp.actives, ...disp.planned].map((sp) => (
                    <Button
                      key={sp.sprintId}
                      variant={
                        disp.pick === sp.sprintId ? "contained" : "outlined"
                      }
                      onClick={() => setDisp({ ...disp, pick: sp.sprintId })}
                    >
                      {sp.name}
                    </Button>
                  ))}
                </Stack>
              )}
              {disp &&
                disp.actives.length === 0 &&
                disp.planned.length === 0 && (
                  <TextField
                    label={t.board.newGroupName}
                    value={disp.newName}
                    onChange={(e) =>
                      setDisp({ ...disp, newName: e.target.value })
                    }
                    fullWidth
                    sx={{ mb: 1 }}
                  />
                )}
              {disp?.rows.map((r, idx) => (
                <Box
                  key={r.itemId}
                  sx={{ display: "flex", alignItems: "center", gap: 1 }}
                >
                  <Box
                    sx={{
                      flex: 1,
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      color: r.toBacklog ? "warning.dark" : "text.primary",
                    }}
                  >
                    {r.title}
                  </Box>
                  {r.toBacklog && (
                    <Typography
                      variant="body2"
                      color="warning.dark"
                      fontWeight={600}
                    >
                      {t.sprintPage.dispRowMarked}
                    </Typography>
                  )}
                  <Button
                    size="small"
                    variant={r.toBacklog ? "contained" : "text"}
                    color={r.toBacklog ? "warning" : "inherit"}
                    aria-label={`${r.toBacklog ? t.sprintPage.dispRowUndo : t.sprintPage.dispRowBacklog} ${r.title}`}
                    onClick={() =>
                      setDisp({
                        ...disp,
                        rows: disp.rows.toSpliced(idx, 1, {
                          ...r,
                          toBacklog: !r.toBacklog,
                        }),
                      })
                    }
                  >
                    {r.toBacklog
                      ? t.sprintPage.dispRowUndo
                      : t.sprintPage.dispRowBacklog}
                  </Button>
                </Box>
              ))}
              {disp && disp.rows.length > 0 && (
                <Typography
                  variant="body2"
                  sx={{ mt: 1 }}
                  color={
                    disp.rows.some((r) => r.toBacklog)
                      ? "warning.dark"
                      : "text.secondary"
                  }
                >
                  {`${t.sprintPage.dispSummaryMove}「${targetGroupName(disp)}」 ${disp.rows.filter((r) => !r.toBacklog).length} ${t.sprintPage.dispSummaryBack} ${disp.rows.filter((r) => r.toBacklog).length} ${t.sprintPage.dispSummaryTail}`}
                </Typography>
              )}
            </DialogContent>
            <DialogActions>
              {/* 0110:取消|进 Backlog|进「组名」三钮(用户拍板一次弹出) */}
              <Button onClick={() => setDisp(null)} disabled={busy}>
                "取消"
              </Button>
              <Button onClick={doBacklogAll} disabled={busy}>
                {t.sprintPage.dispGoBacklog}
              </Button>
              <Button
                variant="contained"
                onClick={confirmDisposition}
                disabled={busy}
              >
                {disp && `进「${targetGroupName(disp)}」`}
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
      const k =
        view === "byStatus"
          ? i.status
          : (i.assignee ?? t.sprintCharts.unassigned);
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const order =
      view === "byStatus"
        ? [...statusOrder, ...counts.keys()].filter(
            (k, idx, a) => counts.has(k) && a.indexOf(k) === idx,
          )
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
