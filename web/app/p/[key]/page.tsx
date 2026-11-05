"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { Dropdown } from "@/components/Dropdown";
import RefreshIcon from "@mui/icons-material/Refresh";
import UndoOutlined from "@mui/icons-material/UndoOutlined";
import {
  Checkbox,
  List,
  ListItem,
  ListItemText,
  DialogActions,
  Tooltip,
} from "@mui/material";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  listSprints,
  sprintAction,
  assignSprint,
  createSprint,
  level1Type,
} from "@/lib/sprints";
import { AssignPopover } from "@/components/AssignPopover";
import type { Signal } from "@/components/Verdict";

type Status = {
  statusId: string;
  name: string;
  icon: string | null;
  statusType: string;
  position: number;
};
type Item = {
  itemId: string;
  parentItemId?: string | null;
  parentNumber?: string | null;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  assigneeColor: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
  feasSignal: Signal | null;
  dueDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  blocked: boolean;
  sprintName: string | null;
};

type Filter = "all" | "unassigned" | "blocked" | string; // string = memberId
type SprintLite = { sprintId: string; name: string; status: string };

export default function BoardPage() {
  const { key } = useParams<{ key: string }>();
  const router = useRouter();
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [newTitle, setNewTitle] = useState("");
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [sprintInfo, setSprintInfo] = useState(""); // YPJ-120:开始动作自动入 sprint 的轻提示
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>("all");
  const [activeSprints, setActiveSprints] = useState<SprintLite[]>([]);
  const [sprints, setSprints] = useState<SprintLite[]>([]);
  const [sprintPickOpen, setSprintPickOpen] = useState(false);
  const [pendingTitle, setPendingTitle] = useState("");
  const [pullOpen, setPullOpen] = useState(false);
  // 0105:S2/S2m(activate)/S3(create)前置确认弹窗;取消=放弃创建(D6)
  const [launchOpen, setLaunchOpen] = useState(false);
  const [launchMode, setLaunchMode] = useState<"activate" | "create">(
    "activate",
  );
  const [launchPick, setLaunchPick] = useState("");
  const [launchName, setLaunchName] = useState("");
  const [backlogItems, setBacklogItems] = useState<Item[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());

  // V9-Q3:看板 30s 自动刷新(手刷按钮同函数)
  useEffect(() => {
    // V11-S1:记录最后访问的项目
    const token = localStorage.getItem("yz-token");
    if (token && key) {
      fetch(`/api/members/me/last-project`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ key: String(key) }),
      }).catch(() => {});
    }
  }, [key]);
  useEffect(() => {
    const t = setInterval(() => load(), 30000);
    return () => clearInterval(t);
  }, []);

  const load = useCallback(async () => {
    try {
      const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
      setStatuses(project.statuses);
      // V9-S1:item 列表自带 feasSignal 冗余,不再单 feasibility 调用
      // YPJ-29:看板数据范围由后端决定(active sprint 的 items;无 active → 全量)
      const board = await api<{ activeGroups: SprintLite[]; items: Item[] }>(
        `/api/projects/${key}/board`,
      );
      setActiveSprints(board.activeGroups ?? []);
      setItems(board.items);
      setSprints(await listSprints(key));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  // 项目级面板(工作流/成员)改动 → 刷新本页数据(面板已上移至 layout,YPJ-13)
  useEffect(() => {
    const h = () => load();
    window.addEventListener("yz:project-changed", h);
    return () => window.removeEventListener("yz:project-changed", h);
  }, [load]);

  function openPull() {
    setPicked(new Set());
    api<Item[]>(`/api/projects/${key}/backlog`)
      .then(setBacklogItems)
      .catch(() => setBacklogItems([]));
    setPullOpen(true);
  }

  async function setBoardItem(itemId: string, on: boolean) {
    await api(`/api/items/${itemId}/board-membership`, {
      method: "PUT",
      body: JSON.stringify({ onBoard: on }),
    });
  }

  async function confirmPull() {
    for (const id of picked) {
      await setBoardItem(id, true);
    }
    setPullOpen(false);
    await load();
  }

  async function addItem(e: React.FormEvent) {
    e.preventDefault();
    if (!newTitle.trim() || loading) return; // loading 未就绪时 sprints 可能是空态,误判场景
    const title = newTitle.trim();
    const active = sprints.filter((s) => s.status === "active");
    const planned = sprints.filter((s) => s.status === "planned");
    if (active.length > 1) {
      // S1m:多进行中组 → 用户选(归属是用户意图,不代选)
      setPendingTitle(title);
      setSprintPickOpen(true);
      return;
    }
    if (active.length === 1) {
      // S1:直挂该组
      await createInGroup(title, active[0].sprintId);
      return;
    }
    if (planned.length > 0) {
      // S2/S2m:激活并放入(确认制;取消=放弃创建,D6)
      setPendingTitle(title);
      setLaunchPick(planned[0].sprintId);
      setLaunchMode("activate");
      setLaunchOpen(true);
      return;
    }
    // 0 进行中 0 计划中:S0(无 level-1 类型)或 S3(有类型无非完成态实例)——查类型区分
    try {
      const l1 = await level1Type(key);
      if (!l1) {
        // S0:无组概念,onBoard 上看板(0101 铁律);请求失败会 throw → 不静默当 S0
        await createFree(title);
        return;
      }
      // S3:新建组并激活;默认名=类型名+实例数+1,撞名递增
      let n = sprints.length + 1;
      while (sprints.some((s) => s.name === `${l1.name} ${n}`)) n += 1;
      setPendingTitle(title);
      setLaunchMode("create");
      setLaunchName(`${l1.name} ${n}`);
      setLaunchOpen(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** S1/S1m/S2/S3 共用:建 item 并挂入组(后端 create 同事务校验 level-1 ∧ 非 completed)。 */
  async function createInGroup(title: string, groupId: string) {
    try {
      await api(`/api/projects/${key}/items`, {
        method: "POST",
        body: JSON.stringify({ title, sprintId: groupId }),
      });
      setNewTitle("");
      setSprintPickOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** 仅 S0(无组概念):建 item + onBoard 上看板(0099;无 active 时不挂组)。 */
  async function createFree(title: string) {
    try {
      const created = await api<{ itemId: string }>(
        `/api/projects/${key}/items`,
        { method: "POST", body: JSON.stringify({ title }) },
      );
      await api(`/api/items/${created.itemId}/board-membership`, {
        method: "PUT",
        body: JSON.stringify({ onBoard: true }),
      });
      setNewTitle("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** 0105:确认=S2 激活并放入 / S3 新建组+激活+放入(组先行,item 后行;激活失败降级为挂入计划中组)。 */
  async function confirmLaunch() {
    const title = pendingTitle;
    setLaunchOpen(false);
    if (launchMode === "activate") {
      if (!launchPick) return;
      try {
        await sprintAction(key, launchPick, "start");
      } catch (err) {
        setError(err instanceof Error ? err.message : t.board.startFailed);
      }
      await createInGroup(title, launchPick);
      return;
    }
    const name = launchName.trim();
    if (!name) return;
    try {
      // 防重:同名组复用不重建(激活失败重试场景)
      const existing = sprints.find((s) => s.name === name);
      const gid = existing
        ? existing.sprintId
        : (await createSprint(key, { name, startDate: null, endDate: null }))
            .sprintId;
      try {
        await sprintAction(key, gid, "start");
      } catch (err) {
        setError(err instanceof Error ? err.message : t.board.startFailed);
      }
      await createInGroup(title, gid);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  // YPJ-29:看板内一键激活 planned sprint(激活后重拉 board,范围收窄)
  async function startSprint(sprintId: string) {
    if (!sprintId) return;
    try {
      await sprintAction(key, sprintId, "start");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
    }
  }

  async function moveTo(itemId: string, status: Status) {
    const item = items.find((i) => i.itemId === itemId);
    if (!item || item.status === status.name) return;
    try {
      setItems((prev) =>
        prev.map((i) =>
          i.itemId === itemId ? { ...i, status: status.name } : i,
        ),
      );
      const resp = await api<{ autoAssignedSprintName?: string | null }>(
        `/api/items/${itemId}`,
        {
          method: "PATCH",
          body: JSON.stringify({ statusItemId: status.statusId }),
        },
      );
      await load();
      if (resp.autoAssignedSprintName) {
        setSprintInfo(
          `已自动加入 sprint「${resp.autoAssignedSprintName}」;不想进可手动移出`,
        );
        setTimeout(() => setSprintInfo(""), 6000);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.moveFailed);
      await load();
    }
  }

  // S7:assignee filter (purely frontend; "unassigned" = assignee is null); sprint scope is determined by the backend board endpoint (YPJ-29)
  const filtered =
    filter === "all"
      ? items
      : filter === "unassigned"
        ? items.filter((i) => !i.assignee)
        : filter === "blocked"
          ? items.filter((i) => i.blocked)
          : items.filter((i) => i.assignee === filter);
  const memberOptions = [
    ...new Set(items.map((i) => i.assignee).filter((a): a is string => !!a)),
  ];

  const finalNames = new Set(
    statuses.filter((s) => s.statusType === "FINAL").map((s) => s.name),
  );
  const byStatusName = new Map<string, Item[]>();
  statuses.forEach((s) => byStatusName.set(s.name, []));
  filtered.forEach((it) => {
    const list = byStatusName.get(it.status) ?? [];
    list.push(it);
    byStatusName.set(it.status, list);
  });

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{String(key).toUpperCase()} · 看板</Typography>
        <IconButton
          size="small"
          aria-label={t.board.refresh}
          onClick={() => load()}
        >
          <RefreshIcon fontSize="small" />
        </IconButton>
        <Stack component="form" direction="row" spacing={1} onSubmit={addItem}>
          <TextField
            size="small"
            placeholder={t.board.addItem}
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
          />
          <Button type="submit" variant="outlined">
            {t.board.addItem}
          </Button>
        </Stack>
        <Box sx={{ ml: "auto" }}>
          <Button size="small" component={Link} href={`/p/${key}/time`}>
            工时
          </Button>
        </Box>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {sprintInfo && (
        <Alert
          severity="success"
          sx={{ mb: 2 }}
          onClose={() => setSprintInfo("")}
        >
          {sprintInfo}
        </Alert>
      )}

      {/* YPJ-29:无 active sprint → 提示条 + 「开始 sprint」(激活后看板自动收窄) */}
      {!loading && activeSprints.length === 0 && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
          <Alert severity="info" sx={{ py: 0.5, flex: 1 }}>
            {sprints.length === 0 ? t.board.noGroups : t.board.noActiveSprint}
          </Alert>
          {sprints.some((s) => s.status === "planned") && (
            <Dropdown
              ariaLabel={t.board.startSprint}
              size="small"
              value=""
              onChange={(v) => startSprint(v)}
              sx={{ width: 240 }}
              options={sprints
                .filter((s) => s.status === "planned")
                .map((s) => ({
                  value: s.sprintId,
                  label: `${t.board.startSprint}: ${s.name}`,
                }))}
            />
          )}
        </Stack>
      )}

      {/* S7:assignee 过滤 chips;sprint 范围由后端 board 端点决定(YPJ-29) */}
      <Stack
        direction="row"
        spacing={1}
        sx={{ mb: 2 }}
        flexWrap="wrap"
        useFlexGap
      >
        <Chip
          label={t.filter.all}
          size="small"
          color={filter === "all" ? "primary" : "default"}
          onClick={() => setFilter("all")}
        />
        <Chip
          label={t.filter.unassigned}
          size="small"
          color={filter === "unassigned" ? "primary" : "default"}
          onClick={() => setFilter("unassigned")}
        />
        <Chip
          label="⛔ 被阻塞"
          size="small"
          color={filter === "blocked" ? "primary" : "default"}
          onClick={() => setFilter("blocked")}
        />
        <Button size="small" variant="outlined" onClick={openPull}>
          {t.board.pullItem}
        </Button>
        {memberOptions.map((name) => (
          <Chip
            key={name}
            label={`👤 ${name}`}
            size="small"
            color={filter === name ? "primary" : "default"}
            onClick={() => setFilter(name)}
          />
        ))}
      </Stack>

      {loading ? (
        <Stack direction="row" spacing={2}>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} variant="rounded" width={280} height={400} />
          ))}
        </Stack>
      ) : (
        <Stack
          direction="row"
          spacing={2}
          sx={{ overflowX: "auto", alignItems: "flex-start", pb: 2 }}
        >
          {statuses.map((s) => {
            const list = byStatusName.get(s.name) ?? [];
            return (
              <Box
                key={s.statusId}
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(s.statusId);
                }}
                onDragLeave={() =>
                  setDragOver((cur) => (cur === s.statusId ? null : cur))
                }
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(null);
                  const itemId = e.dataTransfer.getData("text/plain");
                  if (itemId) moveTo(itemId, s);
                }}
                sx={{
                  minWidth: 280,
                  minHeight: 300,
                  p: 1,
                  borderRadius: 2,
                  bgcolor:
                    dragOver === s.statusId
                      ? "action.hover"
                      : "background.default",
                  border: "1px dashed",
                  borderColor:
                    dragOver === s.statusId ? "primary.main" : "divider",
                }}
              >
                <Typography variant="subtitle2" sx={{ px: 1, py: 0.5 }}>
                  {s.icon ? `${s.icon} ` : ""}
                  {s.name}({list.length}){s.statusType === "FINAL" && " [终态]"}
                </Typography>
                <Stack spacing={1}>
                  {list.length === 0 && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ px: 1 }}
                    >
                      (空)
                    </Typography>
                  )}
                  {list.map((it) => (
                    <Card
                      key={it.itemId}
                      draggable
                      onDragStart={(e) =>
                        e.dataTransfer.setData("text/plain", it.itemId)
                      }
                      onClick={() => router.push(`/p/${key}/i/${it.itemId}`)}
                      sx={{
                        cursor: "pointer",
                        borderLeft:
                          "4px solid " + (it.assigneeColor ?? "#9e9e9e"),
                        transition: "box-shadow .15s, border-color .15s",
                        "&:hover": {
                          boxShadow: 6,
                          borderColor: "primary.main",
                        },
                      }}
                    >
                      <CardContent
                        sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}
                      >
                        <Stack spacing={0.5}>
                          <Stack
                            direction="row"
                            spacing={1}
                            alignItems="center"
                          >
                            {it.parentNumber && (
                              <Link
                                href={`/p/${key}/i/${it.parentItemId}`}
                                style={{ textDecoration: "none" }}
                              >
                                <Typography
                                  variant="caption"
                                  color="primary"
                                  sx={{ whiteSpace: "nowrap" }}
                                >
                                  {it.parentNumber} ›
                                </Typography>
                              </Link>
                            )}
                            <Chip
                              label={it.number}
                              size="small"
                              variant="outlined"
                            />
                            {it.feasSignal && it.feasSignal !== "GREEN" && (
                              <Chip
                                size="small"
                                label={t.signal[it.feasSignal]}
                                color={
                                  it.feasSignal === "RED" ? "error" : "warning"
                                }
                              />
                            )}
                            {it.blocked && (
                              <Chip
                                size="small"
                                color="error"
                                variant="outlined"
                                label="⛔ 被阻塞"
                              />
                            )}
                            {it.sprintName && (
                              <Chip
                                size="small"
                                variant="outlined"
                                label={it.sprintName}
                              />
                            )}
                            <AssignPopover
                              itemId={it.itemId}
                              assignee={it.assignee}
                              onChanged={load}
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
                                  sx={
                                    it.assignee ? undefined : { opacity: 0.55 }
                                  }
                                />
                              )}
                            </AssignPopover>
                            {!finalNames.has(it.status) && (
                              <Tooltip title={t.board.remove}>
                                <IconButton
                                  size="small"
                                  aria-label={t.board.remove}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setBoardItem(it.itemId, false).then(load);
                                  }}
                                  sx={{ alignSelf: "center", mr: -0.5 }}
                                >
                                  <UndoOutlined fontSize="inherit" />
                                </IconButton>
                              </Tooltip>
                            )}
                          </Stack>
                          <Link
                            href={`/p/${key}/i/${it.itemId}`}
                            style={{ textDecoration: "none", color: "inherit" }}
                          >
                            <Typography
                              variant="body2"
                              sx={{ wordBreak: "break-word" }}
                            >
                              {it.title}
                            </Typography>
                          </Link>
                          {(it.dueDate || it.overdue || it.dueSoon) && (
                            <Typography
                              variant="caption"
                              sx={{
                                color: it.overdue
                                  ? "error.main"
                                  : it.dueSoon
                                    ? "warning.main"
                                    : "text.secondary",
                                fontWeight:
                                  it.overdue || it.dueSoon ? 700 : 400,
                              }}
                            >
                              {it.overdue
                                ? "⚠ 超期 "
                                : it.dueSoon
                                  ? "⏳ 将到期 "
                                  : "📅 "}
                              {it.dueDate}
                            </Typography>
                          )}
                          {it.requirements.length > 0 && (
                            <Typography
                              variant="caption"
                              color="text.secondary"
                            >
                              {it.requirements
                                .map((r) =>
                                  r.minLevel
                                    ? `${r.attribute}≥${r.minLevel}`
                                    : r.attribute,
                                )
                                .join(" · ")}
                            </Typography>
                          )}
                        </Stack>
                      </CardContent>
                    </Card>
                  ))}
                </Stack>
              </Box>
            );
          })}
        </Stack>
      )}

      <Dialog open={sprintPickOpen} onClose={() => setSprintPickOpen(false)}>
        <DialogTitle>放入哪个组</DialogTitle>
        <DialogContent>
          <Stack spacing={1} sx={{ mt: 1 }}>
            {activeSprints.map((sp) => (
              <Button
                key={sp.sprintId}
                variant="outlined"
                onClick={() => createInGroup(pendingTitle, sp.sprintId)}
              >
                {sp.name}
              </Button>
            ))}
          </Stack>
        </DialogContent>
      </Dialog>
      {/* 0105 S2/S2m/S3:创建前置确认——激活并放入/新建并激活;取消=放弃创建(D6) */}
      <Dialog open={launchOpen} onClose={() => setLaunchOpen(false)}>
        <DialogTitle>
          {launchMode === "activate" &&
          sprints.filter((s) => s.status === "planned").length > 1
            ? t.board.launchPickTitle
            : t.board.launchTitle}
        </DialogTitle>
        <DialogContent>
          {launchMode === "create" ? (
            <TextField
              label={t.board.newGroupName}
              value={launchName}
              onChange={(e) => setLaunchName(e.target.value)}
              autoFocus
              fullWidth
              sx={{ mt: 1 }}
            />
          ) : sprints.filter((s) => s.status === "planned").length > 1 ? (
            <Stack spacing={1} sx={{ mt: 1 }}>
              {sprints
                .filter((s) => s.status === "planned")
                .map((sp) => (
                  <Button
                    key={sp.sprintId}
                    variant={
                      launchPick === sp.sprintId ? "contained" : "outlined"
                    }
                    onClick={() => setLaunchPick(sp.sprintId)}
                  >
                    {sp.name}
                  </Button>
                ))}
            </Stack>
          ) : (
            <Typography>
              {t.board.launchBody.replace(
                "{name}",
                sprints.find((s) => s.status === "planned")?.name ?? "",
              )}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLaunchOpen(false)}>
            {t.board.launchCancel}
          </Button>
          <Button variant="contained" onClick={confirmLaunch}>
            {launchMode === "create"
              ? t.board.launchCreateConfirm
              : t.board.launchConfirm}
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={pullOpen}
        onClose={() => setPullOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>{t.board.pullTitle}</DialogTitle>
        <DialogContent dividers sx={{ maxHeight: 480 }}>
          <List dense disablePadding>
            {[...(backlogItems ?? [])]
              .sort(
                (a, b) =>
                  Number(a.status === "To Do") - Number(b.status === "To Do"),
              )
              .map((it) => (
                <ListItem key={it.itemId} dense disableGutters>
                  <Checkbox
                    size="small"
                    checked={picked.has(it.itemId)}
                    onChange={() =>
                      setPicked((prev) => {
                        const next = new Set(prev);
                        if (next.has(it.itemId)) next.delete(it.itemId);
                        else next.add(it.itemId);
                        return next;
                      })
                    }
                  />
                  <ListItemText
                    primary={`${it.number} · ${it.title}`}
                    secondary={it.status}
                  />
                </ListItem>
              ))}
            {(backlogItems ?? []).length === 0 && (
              <Typography variant="caption" color="text.secondary">
                {t.board.pullEmpty}
              </Typography>
            )}
          </List>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPullOpen(false)}>取消</Button>
          <Button
            variant="contained"
            onClick={confirmPull}
            disabled={picked.size === 0}
          >
            {t.board.pullConfirm}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
