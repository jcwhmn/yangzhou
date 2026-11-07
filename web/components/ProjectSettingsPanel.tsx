"use client";

// YPJ-151:项目设置——release/milestone 启停(仅存取+UI 门控;服务端不拦截,见 spec 0102)
import {
  Alert,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Button,
  Stack,
  Switch,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

export function ProjectSettingsPanel({
  projectKey,
  open,
  onClose,
  onChanged,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [release, setRelease] = useState(true);
  const [milestone, setMilestone] = useState(true);
  const [error, setError] = useState("");
  // 加载完成前禁用开关/保存:初始拉取落地前用户切换会被响应覆盖(YPJ-151 e2e 实锤:check 后被 setMilestone(false) 冲掉,PATCH 存回旧值)
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError("");
    setLoaded(false);
    api<{
      releaseEnabled: boolean;
      milestoneEnabled: boolean;
    }>(`/api/projects/${projectKey}`)
      .then((p) => {
        setRelease(p.releaseEnabled);
        setMilestone(p.milestoneEnabled);
        setLoaded(true);
      })
      .catch(() => {
        setError(t.settings.loadFailed);
        setLoaded(true);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  async function save() {
    try {
      await api(`/api/projects/${projectKey}/settings`, {
        method: "PATCH",
        body: JSON.stringify({
          releaseEnabled: release,
          milestoneEnabled: milestone,
        }),
      });
      setError("");
      onChanged();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.settings.saveFailed);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t.settings.title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1} sx={{ mt: 1 }}>
          <FormControlLabel
            control={
              <Switch
                checked={release}
                disabled={!loaded}
                onChange={(e) => setRelease(e.target.checked)}
              />
            }
            label={t.settings.release}
          />
          <FormControlLabel
            control={
              <Switch
                checked={milestone}
                disabled={!loaded}
                onChange={(e) => setMilestone(e.target.checked)}
              />
            }
            label={t.settings.milestone}
          />
          <Alert severity="info" variant="outlined" sx={{ py: 0.5 }}>
            {t.settings.hint}
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.settings.cancel}</Button>
        <Button variant="contained" onClick={save} disabled={!loaded}>
          {t.settings.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
