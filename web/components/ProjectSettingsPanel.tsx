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
  Typography,
} from "@mui/material";
import { useEffect, useRef, useState } from "react";
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
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  // 同轮 open 去重 + 首响落定:负载下 effect 可能重入,后到的重复响应不得冲值
  const lastRound = useRef("");
  const settled = useRef(false);

  useEffect(() => {
    if (!open) {
      // 关闭即复位:重开首帧控件不渲染,防残留旧值
      lastRound.current = "";
      setLoaded(false);
      return;
    }
    if (lastRound.current === projectKey) return; // 同轮重复触发
    lastRound.current = projectKey;
    settled.current = false;
    setLoaded(false);
    setError("");
    api<{
      releaseEnabled: boolean;
      milestoneEnabled: boolean;
    }>(`/api/projects/${projectKey}`)
      .then((p) => {
        if (settled.current) return;
        settled.current = true;
        setRelease(p.releaseEnabled);
        setMilestone(p.milestoneEnabled);
        setLoaded(true);
      })
      .catch(() => {
        if (settled.current) return;
        settled.current = true;
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
        {/* 加载完成才渲染开关:控件在 settle 帧前不存在,点击意图不会被
            响应落地前后的再渲染吞掉(e2e 全量负载实锤:先渲染+disabled 门禁
            两轮补丁仍有窗口,2026-11-12 根治) */}
        {!loaded ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {t.settings.loading}
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 1 }}>
            <FormControlLabel
              control={
                <Switch
                  checked={release}
                  onChange={(e) => setRelease(e.target.checked)}
                />
              }
              label={t.settings.release}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={milestone}
                  onChange={(e) => setMilestone(e.target.checked)}
                />
              }
              label={t.settings.milestone}
            />
            <Alert severity="info" variant="outlined" sx={{ py: 0.5 }}>
              {t.settings.hint}
            </Alert>
          </Stack>
        )}
        {error && <Alert severity="error">{error}</Alert>}
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
