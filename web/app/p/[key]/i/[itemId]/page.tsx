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
};
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
  actor: string;
  createdAt: string;
};

type ReqRow = { attribute: string; minLevel: number | null };
type CommentDto = { commentId: string; body: string; author: string; createdAt: string };

const activityLabel: Record<string, string> = {
  created: "创建",
  status_changed: "状态变更",
  title_changed: "标题变更",
  description_changed: "描述变更",
  assigned: "指派",
  unassigned: "取消指派",
  requirement_changed: "需求变更",
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
  const [type, setType] = useState("task");
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [assignOpen, setAssignOpen] = useState(false);
  const [confirmCand, setConfirmCand] = useState<Candidate | null>(null);
  const [reqOpen, setReqOpen] = useState(false);
  const [comments, setComments] = useState<CommentDto[]>([]);
  const [newComment, setNewComment] = useState("");

  const load = useCallback(async () => {
    const it = await api<Item>(`/api/items/${itemId}`);
    setItem(it);
    setTitle(it.title);
    setDescription(it.description ?? "");
    setType(it.type);
    const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
    setStatuses(project.statuses);
    setAttributes(await api<Attribute[]>("/api/attributes"));
    setFeasibility(await api<Feasibility>(`/api/items/${itemId}/feasibility`));
    setCandidates(await api<Candidate[]>(`/api/items/${itemId}/candidates`));
    setActivity(await api<Activity[]>(`/api/items/${itemId}/activity`));
    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
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
      body: JSON.stringify({ title, description: description || null, type }),
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
            <Button variant="contained" onClick={() => setAssignOpen(true)}>
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
                {new Date(a.createdAt).toLocaleString("zh-CN")} · {a.actor} ·{" "}
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
