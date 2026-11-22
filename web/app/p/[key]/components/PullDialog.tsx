"use client";

import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  TextField,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import type { Item } from "../types";

/** YPJ-216(spec 0164 途径③):拉入弹窗——勾选 + 行内预估补录(应用到全部)+ 守卫错误浮出。 */
export default function PullDialog({
  open,
  projectKey,
  onClose,
  onPulled,
}: {
  open: boolean;
  projectKey: string;
  onClose: () => void;
  onPulled: () => void | Promise<void>;
}) {
  const [backlogItems, setBacklogItems] = useState<Item[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [error, setError] = useState("");
  // 行内预估补录:每行 {数值,单位};dirty=用户手动改过的行,「应用到全部」不覆盖
  const [est, setEst] = useState<Record<string, { v: string; u: string }>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [allV, setAllV] = useState("");
  const [allU, setAllU] = useState("hour");

  useEffect(() => {
    if (!open) return;
    setPicked(new Set());
    setError("");
    setEst({});
    setDirty(new Set());
    setAllV("");
    setAllU("hour");
    api<Item[]>(`/api/projects/${projectKey}/backlog`)
      .then(setBacklogItems)
      .catch(() => setBacklogItems([]));
  }, [open, projectKey]);

  const setRowEst = (id: string, patch: Partial<{ v: string; u: string }>) => {
    setEst((prev) => ({ ...prev, [id]: { ...prev[id], v: prev[id]?.v ?? "", u: prev[id]?.u ?? "hour", ...patch } }));
    setDirty((prev) => new Set(prev).add(id));
  };

  const applyAll = () => {
    if (!allV) return;
    setEst((prev) => {
      const next = { ...prev };
      for (const it of backlogItems ?? []) {
        if (dirty.has(it.itemId)) continue; // 已手动改过的行不覆盖
        next[it.itemId] = { v: allV, u: allU };
      }
      return next;
    });
  };

  async function confirmPull() {
    setError("");
    try {
      for (const it of backlogItems ?? []) {
        if (!picked.has(it.itemId)) continue;
        const e = est[it.itemId];
        if (e?.v && it.estimateValue == null) {
          // 守卫需预估:补录先落票(值非法由后端校验,错误浮出)
          await api(`/api/items/${it.itemId}`, {
            method: "PATCH",
            body: JSON.stringify({ estimateValue: e.v, estimateUnit: e.u }),
          });
        }
        await api(`/api/items/${it.itemId}/board-membership`, {
          method: "PUT",
          body: JSON.stringify({ onBoard: true }),
        });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "拉入失败");
      return;
    }
    onClose();
    await onPulled();
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t.board.pullTitle}</DialogTitle>
      <DialogContent dividers sx={{ maxHeight: 480 }}>
        <List dense disablePadding>
          {error && (
            <ListItem dense disableGutters>
              <Typography color="error" variant="body2">
                {error}
              </Typography>
            </ListItem>
          )}
          {/* 应用到全部:一次输入对所有勾选且未手动改过的行生效(SRS §三共同约束) */}
          <ListItem dense disableGutters sx={{ gap: 1 }}>
            <TextField
              size="small"
              label="预估(应用到全部)"
              value={allV}
              onChange={(e) => setAllV(e.target.value)}
              sx={{ width: 140 }}
            />
            <TextField
              size="small"
              select
              label="单位"
              value={allU}
              onChange={(e) => setAllU(e.target.value)}
              sx={{ width: 90 }}
            >
              {["hour", "day", "week"].map((u) => (
                <MenuItem key={u} value={u}>
                  {u}
                </MenuItem>
              ))}
            </TextField>
            <Button size="small" onClick={applyAll} disabled={!allV}>
              应用到全部
            </Button>
          </ListItem>
          {[...(backlogItems ?? [])]
            .sort(
              (a, b) =>
                Number(a.status === "To Do") - Number(b.status === "To Do"),
            )
            .map((it) => {
              const e = est[it.itemId];
              return (
                <ListItem key={it.itemId} dense disableGutters sx={{ gap: 1 }}>
                  <Checkbox
                    size="small"
                    checked={picked.has(it.itemId)}
                    onChange={() =>
                      setPicked((prev) => {
                        const next = new Set(prev);
                        if (next.has(it.itemId)) next.delete(it.itemId);
                        else next.add(it.itemId);
                        return next;
                      })
                    }
                  />
                  <ListItemText
                    primary={`${it.number} · ${it.title}`}
                    secondary={
                      it.estimateValue != null
                        ? `${it.status} · 预估 ${it.estimateValue}${it.estimateUnit ?? ""}`
                        : it.status
                    }
                  />
                  {it.estimateValue == null && picked.has(it.itemId) && (
                    <>
                      <TextField
                        size="small"
                        label="预估"
                        value={e?.v ?? ""}
                        onChange={(ev) => setRowEst(it.itemId, { v: ev.target.value })}
                        sx={{ width: 90 }}
                      />
                      <TextField
                        size="small"
                        select
                        label="单位"
                        value={e?.u ?? "hour"}
                        onChange={(ev) => setRowEst(it.itemId, { u: ev.target.value })}
                        sx={{ width: 80 }}
                      >
                        {["hour", "day", "week"].map((u) => (
                          <MenuItem key={u} value={u}>
                            {u}
                          </MenuItem>
                        ))}
                      </TextField>
                    </>
                  )}
                </ListItem>
              );
            })}
          {(backlogItems ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t.board.pullEmpty}
            </Typography>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button
          variant="contained"
          onClick={confirmPull}
          disabled={picked.size === 0}
        >
          {t.board.pullConfirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
