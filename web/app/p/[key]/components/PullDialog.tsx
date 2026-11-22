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
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import type { Item } from "../types";

/** 拉入弹窗:open 时拉 backlog、picked 局部;确认逐条 PUT 上板(原 page openPull/confirmPull 自包含随迁),完成后 onPulled 上行重拉。 */
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
  const [error, setError] = useState(""); // YPJ-216:守卫 409 要浮出(无预估拉入不再静默吞,2026-11-23 手工实锤)

  useEffect(() => {
    if (!open) return;
    setPicked(new Set());
    setError("");
    api<Item[]>(`/api/projects/${projectKey}/backlog`)
      .then(setBacklogItems)
      .catch(() => setBacklogItems([]));
  }, [open, projectKey]);

  async function confirmPull() {
    setError("");
    try {
      for (const id of picked) {
        await api(`/api/items/${id}/board-membership`, {
          method: "PUT",
          body: JSON.stringify({ onBoard: true }),
        });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "拉入失败");
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
          {[...(backlogItems ?? [])]
            .sort(
              (a, b) =>
                Number(a.status === "To Do") - Number(b.status === "To Do"),
            )
            .map((it) => (
              <ListItem key={it.itemId} dense disableGutters>
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
                  secondary={it.status}
                />
              </ListItem>
            ))}
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
