"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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
import { AppNav } from "@/components/AppNav";
import { SignalChip, VerdictLine, type Signal, type Verdict } from "@/components/Verdict";

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
};
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
type Candidate = {
  rank: number;
  memberId: string;
  displayName: string;
  virtual: boolean;
  signal: Signal;
  missingCount: number;
  totalDelta: number;
  verdicts: Verdict[];
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
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [activity, setActivity] = useState<Activity[]>([]);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [startDate, setStartDate] = useState("");
  const [dueDate, setDueDate] = useState("");
  const [type, setType] = useState("task");
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [assignOpen, setAssignOpen] = useState(false);
  const [confirmCand, setConfirmCand] = useState<Candidate | null>(null);
  const [me, setMe] = useState<{ memberId: string; displayName: string } | null>(null);
  const [memberNames, setMemberNames] = useState<Map<string, string>>(new Map());
  const [reqOpen, setReqOpen] = useState(false);
  const [comments, setComments] = useState<CommentDto[]>([]);
  const [newComment, setNewComment] = useState("");
  const [branchOpen, setBranchOpen] = useState(false);
  const [timeLog, setTimeLog] = useState<{ entries: TimeEntry[]; totalMinutes: number } | null>(null);
  const [manualMinutes, setManualMinutes] = useState("");
  const [manualNote, setManualNote] = useState("");

  const load = useCallback(async () => {
    const [it, project, attrs, feas, cands, acts] = await Promise.all([
      api<Item>(`/api/items/${itemId}`),
      api<{ statuses: Status[] }>(`/api/projects/${key}`),
      api<Attribute[]>("/api/attributes"),
      api<Feasibility>(`/api/items/${itemId}/feasibility`),
      api<Candidate[]>(`/api/items/${itemId}/candidates`),
      api<Activity[]>(`/api/items/${itemId}/activity`),
    ])
    setItem(it); setTitle(it.title); setDescription(it.description ?? ""); setType(it.type)
    setStartDate(it.startDate ?? ""); setDueDate(it.dueDate ?? "")
    setStatuses(project.statuses); setAttributes(attrs)
    setFeasibility(feas); setCandidates(cands); setActivity(acts)
    const allMembers = await api<{ memberId: string; displayName: string; virtual: boolean }[]>("/api/members");
    const current = allMembers.find((m) => !m.virtual);
    if (current) setMe(current);
    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
    api<{ entries: TimeEntry[]; totalMinutes: number }>(`/api/items/${itemId}/time-entries`)
      .then(setTimeLog)
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
    await api(`/api/items/${itemId}`, {
      method: "PATCH",
      body: JSON.stringify({ title, description: description || null, type, startDate, dueDate }),
    });
    await load();
    flashSaved();
  }

  async function moveStatus(statusName: string) {
    const target = statuses.find((s) => s.name === statusName);
    if (!target) return;
    await api(`/api/items/${itemId}`, { method: "PATCH", body: JSON.stringify({ statusItemId: target.statusId }) });
    await load();
  }

  async function assign(memberId: string | null) {
    await api(`/api/items/${itemId}/assignee`, { method: "PUT", body: JSON.stringify({ assigneeItemId: memberId }) });
    setConfirmCand(null);
    setAssignOpen(false);
    await load();
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

  const runningEntry = timeLog?.entries.find((e) => e.endedAt === null) ?? null;

  async function tryAssign(c: Candidate) {
    const unmet = c.verdicts.some((v) => v.kind === "gap" || v.kind === "missing" || v.kind === "unrated");
    if (unmet) setConfirmCand(c);
    else await assign(c.memberId);
  }

  if (!item) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color={error ? "error" : "text.secondary"}>{error || "加载中…"}</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 760, mx: "auto", p: 3 }}>
      <AppNav />
      <Link href={`/p/${key}`} style={{ textDecoration: "none" }}>
        <Typography variant="body2" color="primary" gutterBottom>
          {t.item.back}
        </Typography>
      </Link>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        <Chip label={item.number} />
        {feasibility && <SignalChip signal={feasibility.signal} />}
        {item.assignee && <Chip size="small" label={`👤 ${item.assignee}`} onDelete={() => assign(null)} />}
        <Select
          size="small"
          value={item.status}
          onChange={(e) => moveStatus(String(e.target.value))}
          inputProps={{ "aria-label": "item 状态" }}
          sx={{ ml: "auto", minWidth: 160 }}
        >
          {statuses.map((s) => (
            <MenuItem key={s.statusId} value={s.name}>
              {s.name}
            </MenuItem>
          ))}
        </Select>
      </Stack>

      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}

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
            sx={{ width: 200 }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="截止日期"
            type="date"
            size="small"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            sx={{ width: 200 }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
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

      <Typography variant="h6" sx={{ mt: 3, mb: 1 }}>
        谁来做
      </Typography>
      {!assignOpen ? (
        item.assignee ? (
          <Stack direction="row" spacing={1} alignItems="center">
            <Chip label={`👤 ${item.assignee}`} />
            <Button size="small" onClick={() => setAssignOpen(true)}>
              {t.assign.reassign}
            </Button>
          </Stack>
        ) : (
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              onClick={() => me && assign(me.memberId)}
              disabled={!me}
            >
              {t.assign.assignMe}
            </Button>
            <Button variant="outlined" onClick={() => setAssignOpen(true)}>
              {t.assign.assignMember}
            </Button>
          </Stack>
        )
      ) : (
        <Stack spacing={1}>
          {confirmCand && (
            <Alert
              severity="warning"
              action={
                <Button
                  color="inherit"
                  size="small"
                  onClick={() => {
                    assign(confirmCand.memberId);
                    setAssignOpen(false);
                  }}
                >
                  {t.assign.confirmAnyway}
                </Button>
              }
              onClose={() => setConfirmCand(null)}
            >
              {confirmCand.displayName}:{t.assignDialog.unmetWarning}
            </Alert>
          )}
          {candidates.length === 0 && (
            <Typography color="text.secondary" variant="body2">
              (无候选——词表与成员就绪后可分配)
            </Typography>
          )}
          {candidates.map((c) => {
            const unmet = c.verdicts.filter((v) => v.kind === "gap" || v.kind === "missing" || v.kind === "unrated");
            return (
              <Card
                key={c.memberId}
                variant="outlined"
                sx={{
                  borderColor:
                    c.signal === "RED" ? "error.main" : c.signal === "YELLOW" ? "warning.main" : "success.main",
                }}
              >
                <CardContent sx={{ py: 1, "&:last-child": { pb: 1 } }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip size="small" label={`#${c.rank}`} />
                    <Typography variant="body2">
                      {c.displayName}
                      {c.virtual ? "(虚拟)" : ""}
                    </Typography>
                    <Chip
                      size="small"
                      variant="outlined"
                      color={c.signal === "RED" ? "error" : c.signal === "YELLOW" ? "warning" : "success"}
                      label={`缺门${c.missingCount}·差${c.totalDelta}级`}
                    />
                    <Button
                      size="small"
                      variant="contained"
                      sx={{ ml: "auto" }}
                      onClick={() => {
                        if (unmet.length > 0 || c.missingCount > 0) setConfirmCand(c);
                        else assign(c.memberId);
                      }}
                    >
                      {t.assign.assignBtn}
                    </Button>
                  </Stack>
                  {c.verdicts.map((v, i) => (
                    <VerdictLine key={i} v={v} />
                  ))}
                </CardContent>
              </Card>
            );
          })}
        </Stack>
      )}

      {feasibility && (
        <Box sx={{ mt: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t.item.verdicts}
          </Typography>
          {feasibility.verdicts.length === 0 && <Typography color="text.secondary">(无需求)</Typography>}
          {feasibility.verdicts.map((v, i) => (
            <VerdictLine key={i} v={v} />
          ))}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            缺门 {feasibility.missingCount} · 总差距 {feasibility.totalDelta} 级
          </Typography>
        </Box>
      )}

      <RequirementsDialog
        open={reqOpen}
        onClose={() => setReqOpen(false)}
        onSave={saveRequirements}
        attributes={attributes}
      />

      <Box sx={{ mt: 3 }}>
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

      <Box sx={{ mt: 3 }}>
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
      </Box>

      <CreateBranchDialog
        open={branchOpen}
        onClose={() => setBranchOpen(false)}
        projectKey={String(key)}
        itemNumber={item.number}
        itemTitle={item.title}
        itemId={String(itemId)}
        onCreated={load}
      />

      <Typography variant="h6" sx={{ mt: 3, mb: 1 }}>
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
