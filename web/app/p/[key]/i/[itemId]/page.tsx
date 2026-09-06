"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
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
    await load();
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

      {feasibility && (
        <Box sx={{ mt: 3 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="h6" gutterBottom sx={{ mb: 0 }}>
              谁来做
            </Typography>
            {!assignOpen && (
              <Button size="small" variant="outlined" onClick={() => setAssignOpen(true)}>
                {item.assignee ? t.assign.reassign : t.assign.open}
              </Button>
            )}
            {assignOpen && (
              <Button
                size="small"
                variant="contained"
                onClick={() => {
                  const me = candidates.find((c) => !c.virtual);
                  if (me) tryAssign(me);
                }}
              >
                {t.assign.assignMe}
              </Button>
            )}
          </Stack>
          {confirmCand && (
            <Alert
              severity="warning"
              sx={{ my: 1 }}
              action={
                <Button color="inherit" size="small" onClick={() => assign(confirmCand.memberId)}>
                  {t.assign.confirmAnyway}
                </Button>
              }
              onClose={() => setConfirmCand(null)}
            >
              {confirmCand.displayName}:{t.assignDialog.unmetWarning}
            </Alert>
          )}
          {!assignOpen ? (
            item.assignee ? (
              <Chip size="small" label={`👤 ${item.assignee}`} sx={{ mt: 1 }} />
            ) : (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {t.assign.none}
              </Typography>
            )
          ) : (
            <Stack spacing={1} sx={{ mt: 1 }}>
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
                          onClick={() => tryAssign(c)}
                        >
                          {t.assign.assignTo}
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
        </Box>
      )}

      <Typography variant="h6" sx={{ mt: 3, mb: 1 }}>
        活动日志
      </Typography>
      <Stack spacing={0.5}>
        {activity.length === 0 && <Typography variant="body2" color="text.secondary">(暂无)</Typography>}
        {activity.map((a) => (
          <Typography key={a.objectId} variant="caption" color="text.secondary">
            {new Date(a.createdAt).toLocaleString("zh-CN")} · {a.actor} ·{" "}
            {activityLabel[a.kind] ?? a.kind}
            {a.oldValue || a.newValue ? `: ${a.oldValue ?? ""} → ${a.newValue ?? ""}` : ""}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
