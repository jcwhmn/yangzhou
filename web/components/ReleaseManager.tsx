"use client";

import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, Stack, TextField, Tooltip, Typography } from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

export type Release = {
  releaseId: string;
  name: string;
  status: string;
  targetDate: string | null;
  releasedDate: string | null;
  memberCount: number;
};

const statusIcon = { planned: "○", released: "✅" } as const;

/**
 * V15 Release 管理对话框(spec 0012):内联创建/编辑(名称+目标发布日,可勾选「补建为已发布」§8.2)
 * + 行内单向发布按钮(planned→released,行内确认发布日;released 终态只读,历史保留)
 * + 两段式删除。非空删除 409 由后端裁决,错误消息原样展示。
 */
export function ReleaseManager({
  open,
  onClose,
  projectKey,
  releases,
  onChanged,
}: {
  open: boolean;
  onClose: () => void;
  projectKey: string;
  releases: Release[];
  onChanged: () => void;
}) {
  const [name, setName] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [backfill, setBackfill] = useState(false);
  const [releasedDate, setReleasedDate] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [shippingId, setShippingId] = useState<string | null>(null);
  const [shipDate, setShipDate] = useState(new Date().toISOString().slice(0, 10));
  const [error, setError] = useState("");

  const reset = () => {
    setName("");
    setTargetDate("");
    setBackfill(false);
    setReleasedDate("");
    setEditingId(null);
    setError("");
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      if (editingId) {
        await api(`/api/projects/${projectKey}/releases/${editingId}`, {
          method: "PATCH",
          body: JSON.stringify({ name: name.trim(), targetDate: targetDate || null }),
        });
      } else {
        const body: Record<string, unknown> = { name: name.trim(), targetDate: targetDate || null };
        if (backfill) {
          body.status = "released";
          body.releasedDate = releasedDate || null;
        }
        await api(`/api/projects/${projectKey}/releases`, { method: "POST", body: JSON.stringify(body) });
      }
      reset();
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.release.failed);
    }
  }

  async function ship(m: Release) {
    try {
      await api(`/api/projects/${projectKey}/releases/${m.releaseId}`, {
        method: "PATCH",
        body: JSON.stringify({ status: "released", releasedDate: shipDate }),
      });
      setShippingId(null);
      setError("");
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.release.failed);
    }
  }

  async function remove(m: Release) {
    if (confirmingId !== m.releaseId) {
      setConfirmingId(m.releaseId);
      return;
    }
    try {
      await api(`/api/projects/${projectKey}/releases/${m.releaseId}`, { method: "DELETE" });
      setConfirmingId(null);
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.release.failed);
    }
  }

  return (
    <Dialog open={open} onClose={() => { onClose(); reset(); }} maxWidth="sm" fullWidth>
      <DialogTitle>{t.projectNav.releases}</DialogTitle>
      <DialogContent>
        <Box component="form" onSubmit={submit} sx={{ display: "flex", gap: 1, alignItems: "center", mb: 1, flexWrap: "wrap" }}>
          <TextField
            label={t.release.createName}
            size="small"
            value={name}
            onChange={(e) => setName(e.target.value)}
            sx={{ flex: 1, minWidth: 140 }}
            autoFocus
          />
          <TextField
            label={t.release.targetDate}
            type="date"
            size="small"
            value={targetDate}
            onChange={(e) => setTargetDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <Button type="submit" variant="contained" disabled={!name.trim()}>
            {editingId ? t.release.save : t.release.create}
          </Button>
          {editingId && <Button onClick={reset}>{t.release.cancelShip}</Button>}
        </Box>
        {!editingId && (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
            <FormControlLabel
              control={<Checkbox size="small" checked={backfill} onChange={(e) => setBackfill(e.target.checked)} />}
              label={<Typography variant="caption">{t.release.backfill}</Typography>}
            />
            {backfill && (
              <TextField
                label={t.release.releasedDate}
                type="date"
                size="small"
                value={releasedDate}
                onChange={(e) => setReleasedDate(e.target.value)}
                slotProps={{ inputLabel: { shrink: true } }}
              />
            )}
          </Stack>
        )}
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={0.5}>
          {releases.length === 0 && (
            <Typography variant="body2" color="text.secondary">{t.projectNav.noReleases}</Typography>
          )}
          {releases.map((r) => {
            const released = r.status === "released";
            return (
              <Box
                key={r.releaseId}
                data-testid="release-row"
                sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.5, borderBottom: "1px solid", borderColor: "grey.100" }}
              >
                <Typography variant="body2" sx={{ width: 16, textAlign: "center" }}>
                  {statusIcon[r.status as keyof typeof statusIcon]}
                </Typography>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2">{r.name}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t.release.status[r.status as keyof typeof t.release.status]}
                    {r.targetDate ? ` · ${t.release.targetDate} ${r.targetDate}` : ""}
                    {r.releasedDate ? ` · ${t.release.releasedDate} ${r.releasedDate}` : ""}
                    {` · (${r.memberCount})`}
                  </Typography>
                </Box>
                {r.status === "planned" && shippingId !== r.releaseId && (
                  <Button size="small" onClick={() => { setShippingId(r.releaseId); setShipDate(new Date().toISOString().slice(0, 10)); }}>
                    🚀 {t.release.ship}
                  </Button>
                )}
                {shippingId === r.releaseId && (
                  <>
                    <TextField
                      type="date"
                      size="small"
                      value={shipDate}
                      onChange={(e) => setShipDate(e.target.value)}
                      slotProps={{ inputLabel: { shrink: true } }}
                      sx={{ width: 150 }}
                    />
                    <Button size="small" variant="contained" onClick={() => ship(r)}>{t.release.confirmShip}</Button>
                    <Button size="small" color="inherit" onClick={() => setShippingId(null)}>{t.release.cancelShip}</Button>
                  </>
                )}
                {!released && shippingId !== r.releaseId && (
                  <Tooltip title={t.release.edit}>
                    <IconButton
                      size="small"
                      aria-label={t.release.edit}
                      onClick={() => {
                        setEditingId(r.releaseId);
                        setName(r.name);
                        setTargetDate(r.targetDate ?? "");
                        setError("");
                      }}
                    >
                      <EditIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
                <Tooltip title={t.release.delete}>
                  <IconButton
                    size="small"
                    aria-label={t.release.delete}
                    color={confirmingId === r.releaseId ? "error" : "default"}
                    onClick={() => remove(r)}
                  >
                    {confirmingId === r.releaseId ? (
                      <Typography variant="caption" color="error">{t.release.confirmDelete}</Typography>
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
        <Button onClick={() => { onClose(); reset(); }}>{t.release.close}</Button>
      </DialogActions>
    </Dialog>
  );
}
