"use client";

import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import {
  Button,
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
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type Status = {
  statusId: string;
  name: string;
  icon: string | null;
  isStart: boolean;
  isFinal: boolean;
  position: number;
};
type Transition = { from: string; to: string };

/**
 * Workflow 编辑面板(V4-S1):列增删改(名称/图标/isStart/isFinal/position)+ 迁移表整表替换。
 * 数据驱动 workflow 的管理入口——列是行,迁移表空 = 自由迁移(DOMAIN.md 领域规则 3)。
 * 每次成功变更即回调 onChanged → 看板即时刷新。
 */
export function WorkflowEditor({
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
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [saved, setSaved] = useState<Status[]>([]); // 服务器已保存快照(onBlur 对比基准)
  const [transitions, setTransitions] = useState<Transition[]>([]);
  const [newName, setNewName] = useState("");
  const [newPos, setNewPos] = useState(""); // 空 = 排到最后
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const project = await api<{ statuses: Status[] }>(`/api/projects/${projectKey}`);
      setStatuses(project.statuses);
      setSaved(project.statuses);
      setTransitions(await api<Transition[]>(`/api/projects/${projectKey}/transitions`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [projectKey]);

  useEffect(() => {
    if (open) load();
  }, [open, load]);

  async function patch(statusId: string, changes: Record<string, unknown>) {
    setError("");
    try {
      const updated = await api<Status>(`/api/statuses/${statusId}`, {
        method: "PATCH",
        body: JSON.stringify(changes),
      });
      setStatuses((prev) => prev.map((s) => (s.statusId === statusId ? updated : s)));
      setSaved((prev) => prev.map((s) => (s.statusId === statusId ? updated : s)));
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  async function addStatus() {
    setError("");
    if (!newName.trim()) return;
    const position = newPos.trim() === "" ? null : Number(newPos);
    try {
      const created = await api<Status>(`/api/projects/${projectKey}/statuses`, {
        method: "POST",
        body: JSON.stringify({ name: newName.trim(), ...(position !== null ? { position } : {}) }),
      });
      setStatuses((prev) => [...prev, created]);
      setSaved((prev) => [...prev, created]);
      setNewName("");
      setNewPos("");
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    }
  }

  async function removeStatus(statusId: string) {
    setError("");
    try {
      await api(`/api/statuses/${statusId}`, { method: "DELETE" });
      setStatuses((prev) => prev.filter((s) => s.statusId !== statusId));
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败(可能仍被 item 引用)");
    }
  }

  async function saveTransitions(list: Transition[]) {
    setError("");
    try {
      setTransitions(await api<Transition[]>(`/api/projects/${projectKey}/transitions`, {
        method: "PUT",
        body: JSON.stringify({ transitions: list }),
      }));
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{t.wf.title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          列即状态。迁移表为空 = 自由迁移;配置后仅列出的 from→to 合法。改动即时保存并刷新看板。
        </Typography>
        {error && (
          <Typography color="error" variant="body2" sx={{ mb: 1 }}>
            {error}
          </Typography>
        )}

        <Typography variant="subtitle2" gutterBottom>
          {t.wf.statuses}
        </Typography>
        <Stack spacing={1}>
          {statuses.map((s) => (
            <Stack key={s.statusId} direction="row" spacing={1} alignItems="center">
              <TextField
                size="small"
                value={s.name}
                onChange={(e) =>
                  setStatuses((prev) =>
                    prev.map((x) => (x.statusId === s.statusId ? { ...x, name: e.target.value } : x)),
                  )
                }
                onBlur={(e) => {
                  const base = saved.find((x) => x.statusId === s.statusId);
                  if (e.target.value !== (base?.name ?? "")) patch(s.statusId, { name: e.target.value });
                }}
                sx={{ width: 170 }}
              />
              <TextField
                size="small"
                placeholder="图标"
                value={s.icon ?? ""}
                onChange={(e) =>
                  setStatuses((prev) =>
                    prev.map((x) => (x.statusId === s.statusId ? { ...x, icon: e.target.value } : x)),
                  )
                }
                onBlur={(e) => {
                  const base = saved.find((x) => x.statusId === s.statusId);
                  if (e.target.value !== (base?.icon ?? "")) patch(s.statusId, { icon: e.target.value });
                }}
                sx={{ width: 90 }}
              />
              <Select
                size="small"
                value={s.isStart ? "start" : ""}
                onChange={(e) => patch(s.statusId, { isStart: e.target.value === "start" })}
                sx={{ width: 110 }}
                displayEmpty
              >
                <MenuItem value="">—</MenuItem>
                <MenuItem value="start">{t.wf.start}</MenuItem>
              </Select>
              <Select
                size="small"
                value={s.isFinal ? "final" : ""}
                onChange={(e) => patch(s.statusId, { isFinal: e.target.value === "final" })}
                sx={{ width: 110 }}
                displayEmpty
              >
                <MenuItem value="">—</MenuItem>
                <MenuItem value="final">{t.wf.final}</MenuItem>
              </Select>
              <TextField
                size="small"
                type="number"
                label="位置"
                value={s.position}
                onChange={(e) =>
                  setStatuses((prev) =>
                    prev.map((x) => (x.statusId === s.statusId ? { ...x, position: Number(e.target.value) } : x)),
                  )
                }
                onBlur={(e) => {
                  const base = saved.find((x) => x.statusId === s.statusId);
                  if (Number(e.target.value) !== (base?.position ?? 0)) patch(s.statusId, { position: Number(e.target.value) });
                }}
                sx={{ width: 80 }}
              />
              <IconButton size="small" onClick={() => removeStatus(s.statusId)} aria-label="delete">
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}
          <Stack direction="row" spacing={1} alignItems="center">
            <TextField
              size="small"
              placeholder={t.wf.newStatusName}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              sx={{ width: 170 }}
            />
            <TextField
              size="small"
              type="number"
              placeholder="位置(可选)"
              value={newPos}
              onChange={(e) => setNewPos(e.target.value)}
              sx={{ width: 120 }}
            />
            <Button startIcon={<AddIcon />} onClick={addStatus} disabled={!newName.trim()}>
              {t.wf.addStatus}
            </Button>
          </Stack>
        </Stack>

        <Typography variant="subtitle2" gutterBottom sx={{ mt: 3 }}>
          {t.wf.transitions}
        </Typography>
        <Stack spacing={0.5}>
          {transitions.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t.wf.free}
            </Typography>
          )}
          {transitions.map((tr, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="center">
              <Typography variant="body2" sx={{ minWidth: 60 }}>
                {tr.from}
              </Typography>
              <Typography variant="body2">→</Typography>
              <Typography variant="body2" sx={{ minWidth: 60 }}>
                {tr.to}
              </Typography>
              <Button size="small" onClick={() => saveTransitions(transitions.filter((_, j) => j !== i))}>
                {t.wf.remove}
              </Button>
            </Stack>
          ))}
          <AddTransitionRow statuses={statuses} onAdd={(tr) => saveTransitions([...transitions, tr])} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.attrs.delete === "删除" ? "关闭" : "Close"}</Button>
      </DialogActions>
    </Dialog>
  );
}

function AddTransitionRow({
  statuses,
  onAdd,
}: {
  statuses: Status[];
  onAdd: (tr: Transition) => void;
}) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const ready = from && to && from !== to;
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Select size="small" value={from} displayEmpty onChange={(e) => setFrom(String(e.target.value))} sx={{ width: 150 }}>
        <MenuItem value="" disabled>
          {t.wf.from}
        </MenuItem>
        {statuses.map((s) => (
          <MenuItem key={s.statusId} value={s.name}>
            {s.name}
          </MenuItem>
        ))}
      </Select>
      <Typography variant="body2">→</Typography>
      <Select size="small" value={to} displayEmpty onChange={(e) => setTo(String(e.target.value))} sx={{ width: 150 }}>
        <MenuItem value="" disabled>
          {t.wf.to}
        </MenuItem>
        {statuses.map((s) => (
          <MenuItem key={s.statusId} value={s.name}>
            {s.name}
          </MenuItem>
        ))}
      </Select>
      <Button size="small" variant="outlined" disabled={!ready} onClick={() => onAdd({ from, to })}>
        {t.wf.addTransition}
      </Button>
    </Stack>
  );
}
