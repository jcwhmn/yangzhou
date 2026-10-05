"use client";

import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, Stack, TextField, Tooltip, Typography } from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

export type Milestone = {
  milestoneId: string;
  name: string;
  status: string;
  targetDate: string | null;
};

const statusIcon = { planned: "○", in_progress: "▶", completed: "✓", cancelled: "✗" } as const;

/**
 * V14 Milestone 管理对话框(spec 0011):内联创建/编辑表单 + 行内单向状态按钮
 * (planned→▶开始;in_progress→✓完成/✗取消;终态只读,§11 历史保留)+ 两段式删除。
 * 0..1 in_progress 的 409 由后端裁决,错误消息原样展示。
 */
export function MilestoneManager({
  open,
  onClose,
  projectKey,
  milestones,
  onChanged,
}: {
  open: boolean;
  onClose: () => void;
  projectKey: string;
  milestones: Milestone[];
  onChanged: () => void;
}) {
  const [name, setName] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const reset = () => {
    setName("");
    setTargetDate("");
    setEditingId(null);
    setError("");
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      const body = { name: name.trim(), targetDate: targetDate || null };
      if (editingId) {
        await api(`/api/projects/${projectKey}/milestones/${editingId}`, { method: "PATCH", body: JSON.stringify(body) });
      } else {
        await api(`/api/projects/${projectKey}/milestones`, { method: "POST", body: JSON.stringify(body) });
      }
      reset();
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function transition(m: Milestone, status: string) {
    try {
      await api(`/api/projects/${projectKey}/milestones/${m.milestoneId}`, { method: "PATCH", body: JSON.stringify({ status }) });
      setError("");
      onChanged();
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
      await api(`/api/projects/${projectKey}/milestones/${m.milestoneId}`, { method: "DELETE" });
      setConfirmingId(null);
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  return (
    <Dialog open={open} onClose={() => { onClose(); reset(); }} maxWidth="sm" fullWidth>
      <DialogTitle>{t.projectNav.milestones}</DialogTitle>
      <DialogContent>
        <Box component="form" onSubmit={submit} sx={{ display: "flex", gap: 1, alignItems: "center", mb: 2 }}>
          <TextField
            label={t.milestone.createName}
            size="small"
            value={name}
            onChange={(e) => setName(e.target.value)}
            sx={{ flex: 1 }}
            autoFocus
          />
          <TextField
            label={t.milestone.targetDate}
            type="date"
            size="small"
            value={targetDate}
            onChange={(e) => setTargetDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <Button type="submit" variant="contained" disabled={!name.trim()}>
            {editingId ? t.milestone.save : t.milestone.create}
          </Button>
          {editingId && (
            <Button
              onClick={() => {
                reset();
              }}
            >
              {t.milestone.cancelMs}
            </Button>
          )}
        </Box>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={0.5}>
          {milestones.length === 0 && (
            <Typography variant="body2" color="text.secondary">{t.projectNav.noMilestones}</Typography>
          )}
          {milestones.map((m) => {
            const terminal = m.status === "completed" || m.status === "cancelled";
            return (
              <Box
                key={m.milestoneId}
                sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.5, borderBottom: "1px solid", borderColor: "grey.100" }}
              >
                <Typography variant="body2" sx={{ width: 16, textAlign: "center" }}>
                  {statusIcon[m.status as keyof typeof statusIcon]}
                </Typography>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography
                    variant="body2"
                    sx={{ textDecoration: terminal && m.status === "cancelled" ? "line-through" : "none" }}
                  >
                    {m.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t.milestone.status[m.status as keyof typeof t.milestone.status]}
                    {m.targetDate ? ` · ${m.targetDate}` : ""}
                  </Typography>
                </Box>
                {m.status === "planned" && (
                  <Button size="small" onClick={() => transition(m, "in_progress")}>▶ {t.milestone.start}</Button>
                )}
                {m.status === "in_progress" && (
                  <>
                    <Button size="small" onClick={() => transition(m, "completed")}>✓ {t.milestone.complete}</Button>
                    <Button size="small" color="inherit" onClick={() => transition(m, "cancelled")}>✗ {t.milestone.cancelMs}</Button>
                  </>
                )}
                {!terminal && (
                  <Tooltip title={t.milestone.edit}>
                    <IconButton
                      size="small"
                      aria-label={t.milestone.edit}
                      onClick={() => {
                        setEditingId(m.milestoneId);
                        setName(m.name);
                        setTargetDate(m.targetDate ?? "");
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
                    color={confirmingId === m.milestoneId ? "error" : "default"}
                    onClick={() => remove(m)}
                  >
                    {confirmingId === m.milestoneId ? (
                      <Typography variant="caption" color="error">{t.milestone.confirmDelete}</Typography>
                    ) : (
                      <DeleteIcon fontSize="small" />
                    )}
                  </IconButton>
                </Tooltip>
              </Box>
            );
          })}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { onClose(); reset(); }}>{t.milestone.close}</Button>
      </DialogActions>
    </Dialog>
  );
}
