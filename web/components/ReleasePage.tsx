"use client";

// YPJ-201(US-R2,spec 0134):Releases 管理页——列表(planned 在前/released 置灰)、
// 新建/编辑对话框、发布(终态)、删除(仅 planned)。仿 MilestonePage 自拉数据 + 广播。
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";
import { PageShell } from "@/components/PageShell";

type Release = {
  releaseId: string;
  name: string;
  version: string | null;
  stage: string | null;
  status: string;
  targetDate: string | null;
  releasedDate: string | null;
  createdAt: string;
};

const STAGES = ["alpha", "beta", "rc", "ga"] as const;

// 后端 list 已按 planned 前序/released 后序排好,前端只分组展示
export function ReleasePage() {
  const { key } = useParams<{ key: string }>();
  const [releases, setReleases] = useState<Release[]>([]);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<Release | null>(null);
  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [stage, setStage] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [confirmAction, setConfirmAction] = useState<
    "publish" | "delete" | null
  >(null);
  const [error, setError] = useState("");

  const broadcast = () => window.dispatchEvent(new Event("releases-changed"));

  const load = useCallback(() => {
    api<Release[]>(`/api/projects/${key}/releases`)
      .then(setReleases)
      .catch(() => setReleases([]));
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    setName("");
    setVersion("");
    setStage("");
    setTargetDate("");
    setError("");
    setEditorOpen(true);
  };

  const openEdit = (r: Release) => {
    setEditing(r);
    setName(r.name);
    setVersion(r.version ?? "");
    setStage(r.stage ?? "");
    setTargetDate(r.targetDate ?? "");
    setError("");
    setEditorOpen(true);
  };

  const save = async () => {
    if (!name.trim()) return;
    const body = {
      name: name.trim(),
      version: version.trim() || null,
      stage: stage || null,
      targetDate: targetDate || null,
    };
    try {
      if (editing) {
        await api(`/api/projects/${key}/releases/${editing.releaseId}`, {
          method: "PATCH",
          body: JSON.stringify(body),
        });
      } else {
        await api(`/api/projects/${key}/releases`, {
          method: "POST",
          body: JSON.stringify(body),
        });
      }
      setEditorOpen(false);
      load();
      broadcast();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.releases.failed);
    }
  };

  const doAction = async () => {
    if (!confirmAction || !editing) return;
    try {
      if (confirmAction === "publish") {
        await api(`/api/projects/${key}/releases/${editing.releaseId}`, {
          method: "PATCH",
          body: JSON.stringify({ status: "released" }),
        });
      } else {
        await api(`/api/projects/${key}/releases/${editing.releaseId}`, {
          method: "DELETE",
        });
      }
      setConfirmAction(null);
      load();
      broadcast();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.releases.failed);
      setConfirmAction(null);
    }
  };

  const planned = releases.filter((r) => r.status === "planned");
  const released = releases.filter((r) => r.status === "released");

  const row = (r: Release) => (
    <Stack
      key={r.releaseId}
      direction="row"
      spacing={1}
      alignItems="center"
      data-testid={`release-row-${r.releaseId}`}
      sx={
        r.status === "released"
          ? { opacity: 0.55, pointerEvents: "none" }
          : undefined
      }
    >
      <Typography variant="body1">{r.name}</Typography>
      {r.version && (
        <Typography variant="body2" color="text.secondary">
          {r.version}
        </Typography>
      )}
      {r.stage && <Chip label={r.stage} size="small" variant="outlined" />}
      {r.status === "planned" && r.targetDate && (
        <Typography variant="body2" color="text.secondary">
          {t.releases.targetDate}:{r.targetDate}
        </Typography>
      )}
      {r.status === "released" && (
        <Chip
          data-testid="release-released-badge"
          label={`${t.releases.releasedOn} ${r.releasedDate ?? ""}`}
          size="small"
          color="success"
        />
      )}
      <Box sx={{ flex: 1 }} />
      {r.status === "planned" && (
        <>
          <Button size="small" onClick={() => openEdit(r)}>
            {t.releases.edit}
          </Button>
          <Button
            size="small"
            variant="contained"
            data-testid={`release-publish-${r.releaseId}`}
            onClick={() => {
              setEditing(r);
              setConfirmAction("publish");
            }}
          >
            {t.releases.publish}
          </Button>
          <Button
            size="small"
            color="error"
            data-testid={`release-delete-${r.releaseId}`}
            onClick={() => {
              setEditing(r);
              setConfirmAction("delete");
            }}
          >
            {t.releases.delete}
          </Button>
        </>
      )}
    </Stack>
  );

  return (
    <PageShell>
      <Box data-testid="releases-page">
        <Stack
          direction="row"
          justifyContent="space-between"
          alignItems="center"
          sx={{ mb: 2 }}
        >
          <Typography variant="h6">{t.releases.title}</Typography>
          <Button
            variant="contained"
            data-testid="release-create"
            onClick={openCreate}
          >
            {t.releases.create}
          </Button>
        </Stack>
        {releases.length === 0 && (
          <Typography color="text.secondary" data-testid="releases-empty">
            {t.releases.empty}
          </Typography>
        )}
        {planned.length > 0 && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 1, mb: 1 }}>
              {t.releases.planned}
            </Typography>
            <Stack spacing={1}>{planned.map(row)}</Stack>
          </>
        )}
        {released.length > 0 && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 3, mb: 1 }}>
              {t.releases.released}
            </Typography>
            <Stack spacing={1}>{released.map(row)}</Stack>
          </>
        )}
      </Box>

      {/* 新建/编辑对话框 */}
      <Dialog
        open={editorOpen}
        onClose={() => setEditorOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>
          {editing ? t.releases.edit : t.releases.create}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label={t.releases.name}
              value={name}
              onChange={(e) => setName(e.target.value)}
              data-testid="release-name-input"
              required
              fullWidth
            />
            <TextField
              label={t.releases.version}
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              fullWidth
            />
            <TextField
              select
              label={t.releases.stage}
              value={stage}
              onChange={(e) => setStage(e.target.value)}
              fullWidth
            >
              <MenuItem value="">{t.releases.stageNone}</MenuItem>
              {STAGES.map((s) => (
                <MenuItem key={s} value={s}>
                  {s}
                </MenuItem>
              ))}
            </TextField>
            <DateInput
              label={t.releases.targetDate}
              value={targetDate}
              onChange={setTargetDate}
            />
            {error && <Typography color="error">{error}</Typography>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditorOpen(false)}>
            {t.releases.cancel}
          </Button>
          <Button
            variant="contained"
            disabled={!name.trim()}
            data-testid="release-save"
            onClick={save}
          >
            {t.releases.save}
          </Button>
        </DialogActions>
      </Dialog>

      {/* 发布/删除确认框 */}
      <Dialog
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>
          {confirmAction === "publish" ? t.releases.publish : t.releases.delete}
        </DialogTitle>
        <DialogContent>
          {confirmAction === "publish"
            ? t.releases.confirmPublish
            : t.releases.confirmDelete}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmAction(null)}>
            {t.releases.cancel}
          </Button>
          <Button
            variant="contained"
            color={confirmAction === "delete" ? "error" : "primary"}
            data-testid="release-confirm"
            onClick={doAction}
          >
            {confirmAction === "publish"
              ? t.releases.publish
              : t.releases.delete}
          </Button>
        </DialogActions>
      </Dialog>
    </PageShell>
  );
}
