"use client";

import {
  Alert,
  Box,
  Button,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";
import { PageShell } from "@/components/PageShell";

export type Milestone = {
  milestoneId: string;
  name: string;
  description: string | null;
  owner: string | null;
  status: string;
  targetDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  updatedAt: string | null;
};

type PoolMember = { memberId: string; displayName: string; virtual: boolean };

const statusIcon = {
  planned: "○",
  in_progress: "▶",
  completed: "✓",
  cancelled: "✗",
} as const;

/**
 * Milestone 页(spec 0116 票 1 / US-M6):管理弹窗退役,三组列表(当前=0..1 in_progress /
 * 未来=planned targetDate 升序 nulls last / 历史=终态按 updatedAt 降序)+ 新建/行内编辑表单
 * + 行内单向状态按钮 + 双徽标(overdue 红 / dueSoon 黄,后端算,与 item 同口径)。
 * 操作真值表:planned 可开始/取消;in_progress 可完成/取消;终态只读、删除置灰(API 409 守卫兜底)。
 */
export function MilestonePage() {
  const { key } = useParams<{ key: string }>();
  const [milestones, setMilestones] = useState<Milestone[]>([]);
  const [pool, setPool] = useState<PoolMember[]>([]);
  const [name, setName] = useState("");
  const [owner, setOwner] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [description, setDescription] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [ms, p] = await Promise.all([
        api<Milestone[]>(`/api/projects/${key}/milestones`),
        api<PoolMember[]>(`/api/projects/${key}/members`),
      ]);
      setMilestones(ms);
      setPool(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  const reset = () => {
    setName("");
    setOwner("");
    setTargetDate("");
    setDescription("");
    setEditingId(null);
    setError("");
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      const body = {
        name: name.trim(),
        targetDate: targetDate || null,
        description,
        owner: owner || "",
      };
      if (editingId) {
        await api(`/api/projects/${key}/milestones/${editingId}`, {
          method: "PATCH",
          body: JSON.stringify(body),
        });
      } else {
        await api(`/api/projects/${key}/milestones`, {
          method: "POST",
          body: JSON.stringify(body),
        });
      }
      reset();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function transition(m: Milestone, status: string) {
    try {
      await api(`/api/projects/${key}/milestones/${m.milestoneId}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setError("");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function remove(m: Milestone) {
    if (confirmingId !== m.milestoneId) {
      setConfirmingId(m.milestoneId);
      return;
    }
    try {
      await api(`/api/projects/${key}/milestones/${m.milestoneId}`, {
        method: "DELETE",
      });
      setConfirmingId(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  const current = milestones.filter((m) => m.status === "in_progress");
  const future = milestones
    .filter((m) => m.status === "planned")
    .sort((a, b) =>
      (a.targetDate ?? "9999").localeCompare(b.targetDate ?? "9999"),
    );
  const terminal = milestones
    .filter((m) => m.status === "completed" || m.status === "cancelled")
    .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""));

  const row = (m: Milestone) => {
    const isTerminal = m.status === "completed" || m.status === "cancelled";
    return (
      <Box
        key={m.milestoneId}
        data-testid="milestone-row"
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1,
          py: 0.5,
          borderBottom: "1px solid",
          borderColor: "grey.100",
        }}
      >
        <Typography variant="body2" sx={{ width: 16, textAlign: "center" }}>
          {statusIcon[m.status as keyof typeof statusIcon]}
        </Typography>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            variant="body2"
            sx={{
              textDecoration:
                m.status === "cancelled" ? "line-through" : "none",
            }}
          >
            {m.name}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t.milestone.status[m.status as keyof typeof t.milestone.status]}
            {m.targetDate ? ` · ${m.targetDate}` : ""}
          </Typography>
        </Box>
        {m.overdue && (
          <Typography
            variant="caption"
            color="error"
            data-testid="milestone-badge"
          >
            ⚠ {t.milestone.overdue}
          </Typography>
        )}
        {m.dueSoon && (
          <Typography
            variant="caption"
            color="warning.main"
            data-testid="milestone-badge"
          >
            ⚠ {t.milestone.dueSoon}
          </Typography>
        )}
        {m.status === "planned" && (
          <>
            <Button size="small" onClick={() => transition(m, "in_progress")}>
              ▶ {t.milestone.start}
            </Button>
            <Button
              size="small"
              color="inherit"
              onClick={() => transition(m, "cancelled")}
            >
              ✗ {t.milestone.cancelMs}
            </Button>
          </>
        )}
        {m.status === "in_progress" && (
          <>
            <Button size="small" onClick={() => transition(m, "completed")}>
              ✓ {t.milestone.complete}
            </Button>
            <Button
              size="small"
              color="inherit"
              onClick={() => transition(m, "cancelled")}
            >
              ✗ {t.milestone.cancelMs}
            </Button>
          </>
        )}
        {!isTerminal && (
          <Tooltip title={t.milestone.edit}>
            <IconButton
              size="small"
              aria-label={t.milestone.edit}
              onClick={() => {
                setEditingId(m.milestoneId);
                setName(m.name);
                setOwner(m.owner ?? "");
                setTargetDate(m.targetDate ?? "");
                setDescription(m.description ?? "");
                setError("");
              }}
            >
              <EditIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Tooltip title={t.milestone.delete}>
          <IconButton
            size="small"
            aria-label={t.milestone.delete}
            disabled={isTerminal}
            color={confirmingId === m.milestoneId ? "error" : "default"}
            onClick={() => remove(m)}
          >
            {confirmingId === m.milestoneId ? (
              <Typography variant="caption" color="error">
                {t.milestone.confirmDelete}
              </Typography>
            ) : (
              <DeleteIcon fontSize="small" />
            )}
          </IconButton>
        </Tooltip>
      </Box>
    );
  };

  const groupHeader = (icon: string, label: string, count?: number) => (
    <Typography variant="subtitle2" sx={{ mt: 2, mb: 0.5 }}>
      {icon ? `${icon} ` : ""}
      {label}
      {count !== undefined ? ` (${count})` : ""}
    </Typography>
  );

  return (
    <PageShell>
      <Typography variant="h6" sx={{ mb: 2 }}>
        {t.projectNav.milestones}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        ◆ {t.milestone.timelinePlaceholder}
      </Typography>
      <Box
        component="form"
        onSubmit={submit}
        sx={{
          display: "flex",
          flexWrap: "wrap",
          gap: 1,
          alignItems: "center",
          my: 2,
        }}
      >
        <TextField
          label={t.milestone.createName}
          size="small"
          value={name}
          onChange={(e) => setName(e.target.value)}
          sx={{ width: 200 }}
        />
        <TextField
          select
          label={t.milestone.owner}
          size="small"
          value={owner}
          onChange={(e) => setOwner(e.target.value)}
          sx={{ width: 140 }}
        >
          <MenuItem value="">{t.milestone.ownerNone}</MenuItem>
          {pool.map((p) => (
            <MenuItem key={p.memberId} value={p.memberId}>
              {p.displayName}
            </MenuItem>
          ))}
        </TextField>
        <DateInput
          label={t.milestone.targetDate}
          size="small"
          value={targetDate}
          onChange={setTargetDate}
        />
        <TextField
          label={t.milestone.description}
          size="small"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          sx={{ flex: 1, minWidth: 220 }}
        />
        <Button type="submit" variant="contained" disabled={!name.trim()}>
          {editingId ? t.milestone.save : t.milestone.create}
        </Button>
        {editingId && <Button onClick={reset}>{t.milestone.cancelMs}</Button>}
      </Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={0.5}>
        {milestones.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            {t.milestone.empty}
          </Typography>
        )}
        {milestones.length > 0 && groupHeader("▶", t.milestone.current)}
        {current.map(row)}
        {groupHeader("○", t.milestone.future, future.length)}
        {future.map(row)}
        {milestones.length > 0 && groupHeader("", t.milestone.history)}
        {terminal.map(row)}
      </Stack>
    </PageShell>
  );
}
