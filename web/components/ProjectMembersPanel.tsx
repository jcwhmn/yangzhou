"use client";

import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from "@mui/material";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type Member = { memberId: string; displayName: string; username: string | null; virtual: boolean };
type PoolMember = { memberId: string; displayName: string; virtual: boolean };
type StatusLite = { name: string; isFinal: boolean };
type ItemLite = { assignee: string | null; status: string };

/**
 * 项目成员池管理面板(V4-S2):池 = "合法指派范围"。
 * 无行 = 全 workspace 池可用(存量零配置);有行 = 仅池内可指派/进候选(V3.5-B 语义)。
 */
export function ProjectMembersPanel({
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
  const [pool, setPool] = useState<PoolMember[]>([]);
  const [all, setAll] = useState<Member[]>([]);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [p, a, proj, items] = await Promise.all([
        api<PoolMember[]>(`/api/projects/${projectKey}/members`),
        api<Member[]>("/api/members"),
        api<{ statuses: StatusLite[] }>(`/api/projects/${projectKey}`),
        api<ItemLite[]>(`/api/projects/${projectKey}/items`),
      ]);
      setPool(p);
      setAll(a);
      // V11-S5 成员色点:🟢 有非终态 assignee item / ⚪ 无(按 displayName 聚合,workspace 内唯一)
      const finalNames = new Set(proj.statuses.filter((s) => s.isFinal).map((s) => s.name));
      setBusy(new Set(items.filter((i) => i.assignee && !finalNames.has(i.status)).map((i) => i.assignee!)));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [projectKey]);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  async function add(memberId: string) {
    setError("");
    try {
      await api(`/api/projects/${projectKey}/members`, {
        method: "POST",
        body: JSON.stringify({ memberId }),
      });
      await load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }

  async function remove(memberId: string) {
    setError("");
    try {
      await api(`/api/projects/${projectKey}/members/${memberId}`, { method: "DELETE" });
      await load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "移除失败");
    }
  }

  const inPool = new Set(pool.map((p) => p.memberId));
  const candidates = all.filter((m) => !inPool.has(m.memberId));

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.membersPanel.title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {pool.length === 0
            ? t.membersPanel.poolEmpty
            : t.membersPanel.poolConfigured}
        </Typography>
        {error && (
          <Typography color="error" variant="body2" sx={{ mb: 1 }}>
            {error}
          </Typography>
        )}

        <Stack spacing={0.5} sx={{ mb: 2 }}>
          <Typography variant="caption" color="text.secondary">
            {t.membersPanel.busyLegend}
          </Typography>
          {pool.map((m) => (
            <Stack key={m.memberId} direction="row" spacing={1} alignItems="center" justifyContent="space-between">
              <Stack direction="row" spacing={1} alignItems="center">
                <span aria-label={busy.has(m.displayName) ? "进行中" : "空闲"}>
                  {busy.has(m.displayName) ? "🟢" : "⚪"}
                </span>
                <Typography variant="body2">{m.displayName}</Typography>
                {m.virtual && <Chip size="small" label="虚拟" variant="outlined" />}
              </Stack>
              <Button size="small" color="error" onClick={() => remove(m.memberId)}>
                移除
              </Button>
            </Stack>
          ))}
          {pool.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              (池为空——全 workspace 成员可被指派)
            </Typography>
          )}
        </Stack>

        <Typography variant="subtitle2" gutterBottom>
          {t.membersPanel.addable}
        </Typography>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {candidates.map((m) => (
            <Chip key={m.memberId} label={`+ ${m.displayName}`} onClick={() => add(m.memberId)} />
          ))}
          {candidates.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              (全员已在池中)
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>关闭</Button>
      </DialogActions>
    </Dialog>
  );
}
