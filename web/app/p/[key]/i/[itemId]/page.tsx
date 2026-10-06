"use client";

import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { SignalChip, VerdictLine, type Signal, type Verdict } from "@/components/Verdict";
import { AssignPopover } from "@/components/AssignPopover";

type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  description: string | null;
  type: string;
  status: string;
  assignee: string | null;
  parentItemId: string | null;
  externalRef: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
  startDate: string | null;
  dueDate: string | null;
  gitRefs: { kind: string; repo: string; ref: string; url: string | null; state: string | null }[];
  priority: string | null;
  sprintId: string | null;
  sprintName: string | null;
  releaseId: string | null;
  releaseName: string | null;
  overdue: boolean;
  dueSoon: boolean;
  blocked: boolean;
  createdAt: string | null;
};
type SprintLite = { sprintId: string; name: string; status: string };
type ReleaseLite = { releaseId: string; name: string; status: string };
type Repo = { repoId: string; repo: string };
type Attribute = { attributeId: string; name: string; kind: string; leveled: boolean };
type Feasibility = {
  itemId: string;
  number: string;
  title: string;
  signal: Signal;
  missingCount: number;
  totalDelta: number;
  verdicts: Verdict[];
};
type CommitBlock = {
  repo: string;
  ref: string;
  commits: { sha: string; message: string; author: string | null; date: string | null; url: string | null }[];
};

type Activity = {
  objectId: string;
  kind: string;
  oldValue: string | null;
  newValue: string | null;
  actorMemberId: number | null;
  createdAt: string;
};

type ReqRow = { attribute: string; minLevel: number | null };
type CommentDto = { commentId: string; body: string; author: string; createdAt: string };
type Dep = { dependencyItemId: string; itemId: string; number: string; title: string; statusName: string; final: boolean };
type ChecklistEntry = { checklistItemId: string; text: string; done: boolean };
type Checklist = { entries: ChecklistEntry[]; doneCount: number; totalCount: number };
type TimeEntry = {
  timeEntryId: string;
  member: string;
  startedAt: string;
  endedAt: string | null;
  minutes: number;
  note: string | null;
};

const activityLabel: Record<string, string> = {
  created: "创建",
  status_changed: "状态变更",
  title_changed: "标题变更",
  description_changed: "描述变更",
  assigned: "指派",
  unassigned: "取消指派",
  requirement_changed: "需求变更",
  github_status_changed: "GitHub 流转",
  github_branch_created: "GitHub 分支",
  dates_changed: "日期变更",
};

export default function ItemDetailPage() {
  const { key, itemId } = useParams<{ key: string; itemId: string }>();
  const [item, setItem] = useState<Item | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [feasibility, setFeasibility] = useState<Feasibility | null>(null);
  const [activity, setActivity] = useState<Activity[]>([]);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [startDate, setStartDate] = useState("");
  const [dueDate, setDueDate] = useState("");
  const [priority, setPriority] = useState("");
  const [type, setType] = useState("task");
  const [sprints, setSprints] = useState<SprintLite[]>([]);
  const [releases, setReleases] = useState<ReleaseLite[]>([]);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [memberNames, setMemberNames] = useState<Map<string, string>>(new Map());
  const [reqOpen, setReqOpen] = useState(false);
  const [comments, setComments] = useState<CommentDto[]>([]);
  const [newComment, setNewComment] = useState("");
  const [branchOpen, setBranchOpen] = useState(false);
  const [commits, setCommits] = useState<CommitBlock[] | null>(null);
  const [commitsLoading, setCommitsLoading] = useState(false);
  const [commitsError, setCommitsError] = useState("");
  const [timeLog, setTimeLog] = useState<{ entries: TimeEntry[]; totalMinutes: number } | null>(null);
  const [manualMinutes, setManualMinutes] = useState("");
  const [manualNote, setManualNote] = useState("");
  const [deps, setDeps] = useState<Dep[] | null>(null);
  const [depBlocked, setDepBlocked] = useState(false);
  const [depAddOpen, setDepAddOpen] = useState(false);
  const [checklist, setChecklist] = useState<Checklist | null>(null);
  const [newCheckText, setNewCheckText] = useState("");

  const load = useCallback(async () => {
    const [it, project, attrs, feas, acts, spr, rel] = await Promise.all([
      api<Item>(`/api/items/${itemId}`),
      api<{ statuses: Status[] }>(`/api/projects/${key}`),
      api<Attribute[]>("/api/attributes"),
      api<Feasibility>(`/api/items/${itemId}/feasibility`),
      api<Activity[]>(`/api/items/${itemId}/activity`),
      // sprints 并入首屏:选项一次性到位,避免下拉打开后 children 变化把 MUI 菜单关掉
      api<SprintLite[]>(`/api/projects/${key}/sprints`)
        .catch(
          () =>
            new Promise<SprintLite[]>((res) =>
              setTimeout(() => res(api<SprintLite[]>(`/api/projects/${key}/sprints`)), 1000),
            ),
        ) // CI 冷 JVM 偶发连接重置(status -1),延迟重试,否则静默成空选项
        .catch(() => [] as SprintLite[]),
      api<ReleaseLite[]>(`/api/projects/${key}/releases`).catch(() => [] as ReleaseLite[]),
    ])
    setReleases(rel);
    setItem(it); setTitle(it.title); setDescription(it.description ?? ""); setType(it.type)
    setStartDate(it.startDate ?? ""); setDueDate(it.dueDate ?? ""); setPriority(it.priority ?? "")
    setStatuses(project.statuses); setAttributes(attrs); setSprints(spr)
    setFeasibility(feas); setActivity(acts)
    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
    api<{ entries: TimeEntry[]; totalMinutes: number }>(`/api/items/${itemId}/time-entries`)
      .then(setTimeLog)
      .catch(() => {});
    api<{ dependencies: Dep[]; blocked: boolean }>(`/api/items/${itemId}/dependencies`)
      .then((d) => { setDeps(d.dependencies); setDepBlocked(d.blocked); })
      .catch(() => {});
  }, [key, itemId]);

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, [load]);

  const flashSaved = () => {
    setSaved(true);
    setTimeout(() => setSaved(false), 1500);
  };

  async function saveBasics() {
    try {
      await api(`/api/items/${itemId}`, {
        method: "PATCH",
        body: JSON.stringify({ title, description: description || null, type, startDate, dueDate, priority }),
      });
      await load();
      flashSaved();
    } catch (e) {
      // V13-S4:409 等业务错误浮出(如「开工前请先指派负责人」)
      setError(e instanceof Error ? e.message : "保存失败");
    }
  }

  async function moveStatus(statusName: string) {
    const target = statuses.find((s) => s.name === statusName);
    if (!target) return;
    try {
      await api(`/api/items/${itemId}`, { method: "PATCH", body: JSON.stringify({ statusItemId: target.statusId }) });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  async function assign(memberId: string | null) {
    await api(`/api/items/${itemId}/assignee`, { method: "PUT", body: JSON.stringify({ assigneeItemId: memberId }) });
    await load();
  }

  // V15:拉入/移出 release(值 = 指派,null = 移出;released 也可补录,spec 0012)
  async function assignRelease(releaseId: string | null) {
    try {
      const it = await api<Item>(`/api/items/${itemId}/release`, {
        method: "PUT",
        body: JSON.stringify({ releaseId }),
      });
      setItem(it);
    } catch (e) {
      setError(e instanceof Error ? e.message : "指派失败");
    }
  }

  // V12-S3:指派/移出当前 sprint(值 = 指派,null = 移出;completed sprint 不在选项中)
  async function assignSprint(sprintId: string | null) {
    try {
      const it = await api<Item>(`/api/items/${itemId}/sprint`, {
        method: "PUT",
        body: JSON.stringify({ sprintId }),
      });
      setItem(it);
    } catch (e) {
      setError(e instanceof Error ? e.message : "指派失败");
    }
  }

  async function saveRequirements(rows: ReqRow[]) {
    await api(`/api/items/${itemId}/requirements`, {
      method: "PUT",
      body: JSON.stringify({ requirements: rows.filter((r) => r.attribute) }),
    });
    await load();
    flashSaved();
  }

  async function addComment() {
    if (!newComment.trim()) return;
    await api(`/api/items/${itemId}/comments`, { method: "POST", body: JSON.stringify({ body: newComment.trim() }) });
    setNewComment("");
    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
  }

  async function removeComment(commentId: string) {
    await api(`/api/comments/${commentId}`, { method: "DELETE" });
    setComments((prev) => prev.filter((c) => c.commentId !== commentId));
  }

  async function startTimer() {
    setError("");
    try {
      await api(`/api/items/${itemId}/time-entries`, { method: "POST", body: JSON.stringify({ minutes: null }) });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function stopTimer(entryId: string) {
    setError("");
    try {
      await api(`/api/time-entries/${entryId}/stop`, { method: "POST" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function addManual() {
    const m = Number(manualMinutes);
    if (!m || m <= 0) return;
    setError("");
    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",
        body: JSON.stringify({ minutes: m, note: manualNote.trim() || null }),
      });
      setManualMinutes("");
      setManualNote("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function addDependency(dependsOnItemId: string) {
    if (!dependsOnItemId) return;
    setError("");
    try {
      await api(`/api/items/${itemId}/dependencies`, {
        method: "POST",
        body: JSON.stringify({ dependsOnItemId }),
      });
      setDepAddOpen(false);
      const d = await api<{ dependencies: Dep[]; blocked: boolean }>(`/api/items/${itemId}/dependencies`);
      setDeps(d.dependencies); setDepBlocked(d.blocked);
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }

  async function removeDep(rowId: string) {
    await api(`/api/dependencies/${rowId}`, { method: "DELETE" }).catch(() => {});
    const d = await api<{ dependencies: Dep[]; blocked: boolean }>(`/api/items/${itemId}/dependencies`);
    setDeps(d.dependencies); setDepBlocked(d.blocked);
  }

  async function reloadChecklist() {
    setChecklist(await api<Checklist>(`/api/items/${itemId}/checklist`).catch(() => null));
  }

  async function addCheckEntry() {
    if (!newCheckText.trim()) return;
    setError("");
    try {
      await api(`/api/items/${itemId}/checklist`, {
        method: "POST",
        body: JSON.stringify({ text: newCheckText.trim() }),
      });
      setNewCheckText("");
      await reloadChecklist();
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }

  async function toggleCheckEntry(entryId: string, done: boolean) {
    await api(`/api/checklist/${entryId}`, { method: "PATCH", body: JSON.stringify({ done }) }).catch(() => {});
    await reloadChecklist();
  }

  async function removeCheckEntry(entryId: string) {
    await api(`/api/checklist/${entryId}`, { method: "DELETE" }).catch(() => {});
    await reloadChecklist();
  }

  const runningEntry = timeLog?.entries.find((e) => e.endedAt === null) ?? null;

  if (!item) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color={error ? "error" : "text.secondary"}>{error || "加载中…"}</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 1100, mx: "auto", p: 3 }}>
      <Link href={`/p/${key}`} style={{ textDecoration: "none" }}>
        <Typography variant="body2" color="primary" gutterBottom>
          {t.item.back}
        </Typography>
      </Link>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        <Chip label={item.number} />
        {feasibility && <SignalChip signal={feasibility.signal} />}
        {item.assignee ? (
          <AssignPopover itemId={itemId} assignee={item.assignee} onChanged={load}>
            {(onClick) => (
              <Chip size="small" label={`👤 ${item.assignee}`} onClick={onClick} onDelete={() => assign(null)} />
            )}
          </AssignPopover>
        ) : (
          <AssignPopover itemId={itemId} assignee={null} onChanged={load}>
            {(onClick) => (
              <Chip
                size="small"
                variant="outlined"
                label={t.assign.open}
                onClick={onClick}
                sx={{ opacity: 0.55 }}
              />
            )}
          </AssignPopover>
        )}
        <Select
          size="small"
          value={item.status}
          onChange={(e) => moveStatus(String(e.target.value))}
          inputProps={{ "aria-label": "item 状态" }}
          sx={{ minWidth: 160 }}
        >
          {statuses.map((s) => (
            <MenuItem key={s.statusId} value={s.name}>
              {s.name}
            </MenuItem>
          ))}
        </Select>
      </Stack>

      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}

      <Box sx={{ display: "flex", gap: 4, alignItems: "flex-start" }}>
      <Box sx={{ flex: 1, minWidth: 0 }}>
      <Stack spacing={2} component="section">
        <TextField label={t.item.title} value={title} onChange={(e) => setTitle(e.target.value)} fullWidth />
        <TextField
          label={t.item.description}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          multiline
          minRows={2}
          fullWidth
        />
        <Stack direction="row" spacing={1}>
          <TextField
            label="开始日期"
            type="date"
            size="small"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            sx={{ width: 180 }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="截止日期"
            type="date"
            size="small"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            sx={{ width: 180 }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            select
            label={t.item.priority}
            size="small"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
            sx={{ width: 110 }}
          >
            <MenuItem value="">无</MenuItem>
            {["P0", "P1", "P2", "P3"].map((p) => (
              <MenuItem key={p} value={p}>
                {p}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label={t.item.sprint}
            size="small"
            value={item.sprintId ?? ""}
            onChange={(e) => assignSprint(e.target.value || null)}
            sx={{ width: 170 }}
          >
            <MenuItem value="">无</MenuItem>
            {sprints
              .filter((s) => s.status !== "completed" || s.sprintId === item.sprintId)
              .map((s) => (
                <MenuItem key={s.sprintId} value={s.sprintId} disabled={s.status === "completed"}>
                  {s.name}
                </MenuItem>
              ))}
          </TextField>
          {item.sprintId && (
            <Link href={`/p/${key}/sprint/${item.sprintId}`} style={{ textDecoration: "none", alignSelf: "center" }}>
              <Typography variant="caption" color="primary">
                {item.sprintName} ↗
              </Typography>
            </Link>
          )}
          <TextField
            select
            label={t.item.release}
            size="small"
            value={item.releaseId ?? ""}
            onChange={(e) => assignRelease(e.target.value || null)}
            sx={{ width: 170 }}
          >
            <MenuItem value="">无</MenuItem>
            {releases.map((r) => (
              <MenuItem key={r.releaseId} value={r.releaseId}>
                {r.name}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <TextField select label={t.item.type} value={type} onChange={(e) => setType(e.target.value)} sx={{ width: 200 }}>
          {["task", "bug", "goal", "story"].map((tp) => (
            <MenuItem key={tp} value={tp}>
              {tp}
            </MenuItem>
          ))}
        </TextField>
        <Stack direction="row" spacing={1} alignItems="center">
          <Button variant="contained" onClick={saveBasics}>
            {t.item.save}
          </Button>
          {saved && <Typography variant="body2" color="text.secondary">{t.item.saved}</Typography>}
          <Button
            size="small"
            color="error"
            onClick={() => {
              if (confirm(`删除 item ${item.number}?`)) {
                api(`/api/items/${itemId}`, { method: "DELETE" })
                  .then(() => (window.location.href = `/p/${key}`))
                  .catch((e) => setError(e.message));
              }
            }}
          >
            删除
          </Button>
        </Stack>
      </Stack>

      <Typography variant="h6" sx={{ mb: 1 }}>
        评论
      </Typography>
      <Stack spacing={1} sx={{ mb: 2 }}>
        {comments.map((c) => (
          <Box key={c.commentId} sx={{ p: 1.5, bgcolor: "background.paper", borderRadius: 1, border: "1px solid", borderColor: "divider" }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Typography variant="caption" color="text.secondary">
                {c.author} · {new Date(c.createdAt).toLocaleString("zh-CN")}
              </Typography>
              <Button size="small" color="error" onClick={() => removeComment(c.commentId)}>
                删除
              </Button>
            </Stack>
            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
              {c.body}
            </Typography>
          </Box>
        ))}
      </Stack>
      <Stack direction="row" spacing={1}>
        <TextField
          size="small"
          placeholder="写评论…"
          value={newComment}
          onChange={(e) => setNewComment(e.target.value)}
          fullWidth
          multiline
          maxRows={3}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && newComment.trim()) {
              e.preventDefault();
              addComment();
            }
          }}
        />
        <Button variant="outlined" onClick={addComment} disabled={!newComment.trim()}>
          发送
        </Button>
      </Stack>

      {activity.length > 0 && (
        <Box sx={{ mt: 3 }}>
          <Typography variant="h6" gutterBottom>
            活动日志
          </Typography>
          <Stack spacing={0.5}>
            {activity.map((a) => (
              <Typography key={a.objectId} variant="caption" color="text.secondary">
                {new Date(a.createdAt).toLocaleString("zh-CN")} ·{" "}
                {a.actorMemberId == null ? "GitHub" : memberNames.get(String(a.actorMemberId)) ?? `#${a.actorMemberId}`} ·{" "}
                {activityLabel[a.kind] ?? a.kind}
                {a.oldValue || a.newValue ? `: ${a.oldValue ?? ""} → ${a.newValue ?? ""}` : ""}
              </Typography>
            ))}
          </Stack>
        </Box>
      )}

      {feasibility && (
        <Box sx={{ mt: 3 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="h6">{t.item.verdicts}</Typography>
            <Button size="small" variant="outlined" onClick={() => setReqOpen(true)}>
              编辑需求
            </Button>
          </Stack>
          {feasibility.verdicts.length === 0 && <Typography color="text.secondary">(无需求)</Typography>}
          {feasibility.verdicts.map((v, i) => (
            <VerdictLine key={i} v={v} />
          ))}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            缺门 {feasibility.missingCount} · 总差距 {feasibility.totalDelta} 级
          </Typography>
        </Box>
      )}

      {/* 评论/日志/判定/需求/依赖+清单/工时/GitHub 全部单页直出,无页签(YPJ-6 反馈) */}

      <Box>
        <Typography variant="h6">{t.item.requirements}</Typography>
          {(item.requirements ?? []).length === 0 ? (
            <Typography color="text.secondary" variant="body2">
              (无需求——判定聚合在上方)
            </Typography>
          ) : (
            <Stack spacing={0.5}>
              {item.requirements.map((r, i) => (
                <Stack key={i} direction="row" spacing={1} alignItems="center">
                  <Chip size="small" variant="outlined" label={r.attribute} />
                  <Typography variant="body2">{r.minLevel ? `≥${r.minLevel}` : "在场即可"}</Typography>
                </Stack>
              ))}
            </Stack>
          )}
          <Button size="small" variant="outlined" onClick={() => setReqOpen(true)} sx={{ mt: 2 }}>
            编辑需求
          </Button>
        </Box>

      <RequirementsDialog
        open={reqOpen}
        onClose={() => setReqOpen(false)}
        onSave={saveRequirements}
        attributes={attributes}
      />

      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">检查清单</Typography>
          {checklist && checklist.totalCount > 0 && (
            <Chip size="small" label={`${checklist.doneCount}/${checklist.totalCount}`} color={checklist.doneCount === checklist.totalCount ? "success" : "default"} />
          )}
        </Stack>
        <Stack spacing={0.5}>
          {(checklist?.entries ?? []).map((e) => (
            <Stack key={e.checklistItemId} direction="row" spacing={1} alignItems="center">
              <input
                type="checkbox"
                checked={e.done}
                onChange={() => toggleCheckEntry(e.checklistItemId, !e.done)}
              />
              <Typography variant="body2" sx={{ textDecoration: e.done ? "line-through" : "none", color: e.done ? "text.secondary" : "inherit" }}>
                {e.text}
              </Typography>
              <Button size="small" color="error" onClick={() => removeCheckEntry(e.checklistItemId)}>
                删除
              </Button>
            </Stack>
          ))}
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
          <TextField
            size="small"
            placeholder="加一条检查项…"
            value={newCheckText}
            onChange={(e) => setNewCheckText(e.target.value)}
            sx={{ width: 300 }}
          />
          <Button size="small" variant="outlined" onClick={addCheckEntry} disabled={!newCheckText.trim()}>
            添加
          </Button>
        </Stack>
      </Box>

      <Box sx={{ mt: 3 }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">依赖</Typography>
          {depBlocked && <Chip size="small" color="error" label="⛔ 被阻塞" />}
          <Button size="small" variant="outlined" onClick={() => setDepAddOpen((v) => !v)}>
            加依赖
          </Button>
        </Stack>
        {depAddOpen && <DependencyAddForm projectKey={String(key)} selfId={String(itemId)} onAdd={addDependency} />}
        {(deps ?? []).length === 0 ? (
          <Typography variant="caption" color="text.secondary">(无依赖)</Typography>
        ) : (
          <Stack spacing={0.5}>
            {(deps ?? []).map((d) => (
              <Stack key={d.dependencyItemId} direction="row" spacing={1} alignItems="center">
                <Chip size="small" variant="outlined" label={d.number} />
                <Typography variant="caption" noWrap sx={{ maxWidth: 220 }}>{d.title}</Typography>
                <Chip size="small" label={d.statusName} color={d.final ? "success" : "default"} />
                {!d.final && <Chip size="small" color="error" label="阻塞中" />}
                <Button size="small" color="error" onClick={() => removeDep(d.dependencyItemId)}>移除</Button>
              </Stack>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">{t.time.section}</Typography>
          {timeLog && timeLog.totalMinutes > 0 && (
            <Typography variant="body2" color="text.secondary">
              {t.time.fmt(timeLog.totalMinutes)}
            </Typography>
          )}
          {runningEntry ? (
            <Chip size="small" color="warning" label={`${t.time.running} ${t.time.fmt(timeLog!.entries.find((e) => e.endedAt === null)!.minutes)}`} />
          ) : (
            <Button size="small" variant="outlined" onClick={startTimer}>
              {t.time.start}
            </Button>
          )}
        </Stack>
        {runningEntry && (
          <Button size="small" color="error" variant="contained" onClick={() => stopTimer(runningEntry.timeEntryId)} sx={{ mb: 1 }}>
            {t.time.stop}
          </Button>
        )}
        {timeLog && timeLog.entries.length > 0 && (
          <Stack spacing={0.5} sx={{ mb: 1 }}>
            {timeLog.entries.map((e) => (
              <Stack key={e.timeEntryId} direction="row" spacing={1} alignItems="center">
                <Typography variant="caption" color="text.secondary">
                  {e.member} · {new Date(e.startedAt).toLocaleString("zh-CN")}
                  {e.endedAt ? ` → ${new Date(e.endedAt).toLocaleTimeString("zh-CN")}` : ` → ${t.time.running}`}
                </Typography>
                <Typography variant="caption">{t.time.fmt(e.minutes)}</Typography>
                {e.note && (
                  <Typography variant="caption" color="text.secondary">
                    {e.note}
                  </Typography>
                )}
                {e.endedAt === null && (
                  <Button size="small" color="error" onClick={() => stopTimer(e.timeEntryId)}>
                    {t.time.stop}
                  </Button>
                )}
              </Stack>
            ))}
          </Stack>
        )}
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
          <TextField
            size="small"
            label={t.time.minutesLabel}
            type="number"
            value={manualMinutes}
            onChange={(e) => setManualMinutes(e.target.value)}
            sx={{ width: 140 }}
          />
          <TextField
            size="small"
            label={t.time.noteLabel}
            value={manualNote}
            onChange={(e) => setManualNote(e.target.value)}
            sx={{ width: 220 }}
          />
          <Button size="small" variant="outlined" onClick={addManual} disabled={!manualMinutes}>
            {t.time.addManual}
          </Button>
        </Stack>
      </Box>

      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">{t.gh.gitRefs}</Typography>
          <Button size="small" variant="outlined" onClick={() => setBranchOpen(true)}>
            {t.gh.branch}
          </Button>
        </Stack>
        {(item.gitRefs?.length ?? 0) === 0 && (
          <Typography color="text.secondary" variant="body2">
            {t.gh.gitRefsEmpty}
          </Typography>
        )}
        <Stack spacing={0.5}>
          {(item.gitRefs ?? []).map((r) => (
            <Stack key={`${r.kind}-${r.repo}-${r.ref}`} direction="row" spacing={1} alignItems="center">
              <Chip
                size="small"
                variant="outlined"
                label={r.kind === "pr" ? t.gh.statePr : t.gh.stateBranch}
                color={r.kind === "pr" ? "secondary" : "default"}
              />
              {r.kind === "pr" && r.state && (
                <Chip
                  size="small"
                  label={r.state === "merged" ? t.gh.stateMerged : r.state === "open" ? t.gh.stateOpen : t.gh.stateClosed}
                  color={r.state === "merged" ? "success" : r.state === "open" ? "primary" : "default"}
                />
              )}
              {r.url ? (
                <a href={r.url} target="_blank" rel="noreferrer">
                  <Typography variant="body2">{r.ref}</Typography>
                </a>
              ) : (
                <Typography variant="body2">{r.ref}</Typography>
              )}
              <Typography variant="caption" color="text.secondary">
                {r.repo}
              </Typography>
            </Stack>
          ))}
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 2, mb: 1 }}>
          <Typography variant="h6">{t.gh.commitsTitle}</Typography>
          <Button
            size="small"
            variant="outlined"
            onClick={() => {
              setCommitsError("");
              setCommitsLoading(true);
              api<CommitBlock[]>(`/api/items/${itemId}/commits`)
                .then(setCommits)
                .catch((e) => setCommitsError(e instanceof Error ? e.message : "拉取失败"))
                .finally(() => setCommitsLoading(false));
            }}
            disabled={commitsLoading}
          >
            {t.gh.loadCommits}
          </Button>
        </Stack>
        {commitsError && <Typography color="error" variant="body2">{commitsError}</Typography>}
        {commits === null && !commitsError && (
          <Typography color="text.secondary" variant="body2">
            {t.gh.commitsHint}
          </Typography>
        )}
        {(commits ?? []).map((block) => (
          <Box key={`${block.repo}-${block.ref}`} sx={{ mb: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {block.repo} · {block.ref}
            </Typography>
            {block.commits.length === 0 && (
              <Typography variant="caption" color="text.secondary">
                {t.gh.noCommits}
              </Typography>
            )}
            {block.commits.map((c) => (
              <Typography key={c.sha} variant="caption" color="text.secondary" component="div">
                {c.sha.slice(0, 7)} {c.message}
                {c.author ? ` · ${c.author}` : ""}
              </Typography>
            ))}
          </Box>
        ))}
      <CreateBranchDialog
        open={branchOpen}
        onClose={() => setBranchOpen(false)}
        projectKey={String(key)}
        itemNumber={item.number}
        itemTitle={item.title}
        itemId={String(itemId)}
        onCreated={load}
      />

      </Box>

      <Box sx={{ width: 240, flexShrink: 0 }}>
        <Typography variant="caption" color="text.secondary">
          创建
        </Typography>
        <Typography variant="body2" sx={{ mb: 2 }}>
          {item.createdAt ? new Date(item.createdAt).toLocaleString("zh-CN") : "—"}
        </Typography>
        <Stack spacing={1} alignItems="flex-start">
          {item.overdue && <Chip size="small" color="error" label="已超期" />}
          {item.dueSoon && !item.overdue && <Chip size="small" color="warning" label="临期(3 天内)" />}
          {item.blocked && <Chip size="small" color="error" label="被阻塞" />}
        </Stack>
      </Box>
      </Box>
    </Box>
    </Box>
  );
}

function RequirementsDialog({
  open,
  onClose,
  onSave,
  attributes,
}: {
  open: boolean;
  onClose: () => void;
  onSave: (rows: ReqRow[]) => void;
  attributes: Attribute[];
}) {
  const [rows, setRows] = useState<ReqRow[]>([{ attribute: "", minLevel: null }]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.item.requirements}</DialogTitle>
      <DialogContent>
        <Stack spacing={1}>
          {rows.map((r, idx) => (
            <Stack key={idx} direction="row" spacing={1} alignItems="center">
              <Select
                size="small"
                value={r.attribute}
                onChange={(e) =>
                  setRows((prev) => prev.map((x, i) => (i === idx ? { ...x, attribute: String(e.target.value) } : x)))
                }
                sx={{ minWidth: 200 }}
                displayEmpty
              >
                <MenuItem value="" disabled>
                  {t.item.attribute}
                </MenuItem>
                {attributes.map((a) => (
                  <MenuItem key={a.attributeId} value={a.name}>
                    {a.name}
                  </MenuItem>
                ))}
              </Select>
              <Select
                size="small"
                value={r.minLevel ?? ""}
                onChange={(e) =>
                  setRows((prev) =>
                    prev.map((x, i) =>
                      i === idx ? { ...x, minLevel: String(e.target.value) === "" ? null : Number(e.target.value) } : x,
                    ),
                  )
                }
                sx={{ minWidth: 160 }}
                displayEmpty
              >
                <MenuItem value="">{t.item.none}</MenuItem>
                {[1, 2, 3, 4].map((l) => (
                  <MenuItem key={l} value={l}>
                    ≥{l}
                  </MenuItem>
                ))}
              </Select>
              <IconButton onClick={() => setRows((prev) => prev.filter((_, i) => i !== idx))} aria-label="delete">
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}
          <Button startIcon={<AddIcon />} onClick={() => setRows((prev) => [...prev, { attribute: "", minLevel: null }])}>
            {t.item.addRequirement}
          </Button>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.assignDialog.close}</Button>
        <Button variant="contained" onClick={() => onSave(rows.filter((r) => r.attribute))}>
          {t.item.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function CreateBranchDialog({
  open,
  onClose,
  projectKey,
  itemNumber,
  itemTitle,
  itemId,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  projectKey: string;
  itemNumber: string;
  itemTitle: string;
  itemId: string;
  onCreated: () => void;
}) {
  const [repos, setRepos] = useState<Repo[]>([]);
  const [repoId, setRepoId] = useState("");
  const [baseBranch, setBaseBranch] = useState("main");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError("");
    api<Repo[]>(`/api/projects/${projectKey}/repos`)
      .then((rs) => {
        setRepos(rs);
        setRepoId(rs[0]?.repoId ?? "");
      })
      .catch((e) => setError(e.message));
    // 预填 <KEY>-<number>-<slug>(可改);slug = 标题小写、非字母数字转 -
    const keyPart = itemNumber.split("-")[0];
    const numPart = itemNumber.split("-")[1] ?? "";
    const slug = itemTitle
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 40);
    setName([keyPart, numPart, slug].filter(Boolean).join("-"));
  }, [open, projectKey, itemNumber, itemTitle]);

  async function create() {
    setError("");
    setBusy(true);
    try {
      await api(`/api/items/${itemId}/branches`, {
        method: "POST",
        body: JSON.stringify({ repoId, baseBranch: baseBranch.trim(), name: name.trim() }),
      });
      onClose();
      onCreated();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.gh.branchTitle}</DialogTitle>
      <DialogContent>
        {error && (
          <Typography color="error" sx={{ mb: 1 }}>
            {error}
          </Typography>
        )}
        {repos.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {t.gh.repoEmpty}
          </Typography>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label={t.gh.branchRepo} value={repoId} onChange={(e) => setRepoId(e.target.value)}>
            {repos.map((r) => (
              <MenuItem key={r.repoId} value={r.repoId}>
                {r.repo}
              </MenuItem>
            ))}
          </TextField>
          <TextField label={t.gh.branchBase} value={baseBranch} onChange={(e) => setBaseBranch(e.target.value)} />
          <TextField label={t.gh.branchName} value={name} onChange={(e) => setName(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.assignDialog.close}</Button>
        <Button variant="contained" onClick={create} disabled={busy || !repoId || !name.trim() || !baseBranch.trim()}>
          {t.gh.branchCreate}
        </Button>
      </DialogActions>
    </Dialog>
  );
}


function DependencyAddForm({
  projectKey,
  selfId,
  onAdd,
}: {
  projectKey: string;
  selfId: string;
  onAdd: (itemId: string) => void;
}) {
  const [items, setItems] = useState<{ itemId: string; number: string; title: string }[]>([]);
  const [pick, setPick] = useState("");

  useEffect(() => {
    api<{ itemId: string; number: string; title: string }[]>(`/api/projects/${projectKey}/items`)
      .then((list) => setItems(list.filter((i) => i.itemId !== selfId)))
      .catch(() => setItems([]));
  }, [projectKey, selfId]);
  return (
    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
      <select
        aria-label="被依赖 item"
        value={pick}
        onChange={(e) => setPick(e.target.value)}
        style={{ padding: "6px", borderRadius: 4 }}
      >
        <option value="">选择被依赖 item…</option>
        {items.map((i) => (
          <option key={i.itemId} value={i.itemId}>
            {i.number} {i.title}
          </option>
        ))}
      </select>
      <Button size="small" variant="contained" disabled={!pick} onClick={() => onAdd(pick)} aria-label="确认添加依赖">
        添加
      </Button>
    </Stack>
  );
}
