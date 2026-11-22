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
import EstimateFillDialog from "./EstimateFillDialog";

/** YPJ-216(spec 0164 途径③):拉入弹窗——纯勾选;完成后缺预估 → EstimateFillDialog 补录后继续拉入。 */
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
  // 补录阶段:非空 = 勾选票中有缺预估,先弹补录框(列出全部需录入票)
  const [fillItems, setFillItems] = useState<
    { itemId: string; number: number | string; title: string }[]
  >([]);

  useEffect(() => {
    if (!open) return;
    setPicked(new Set());
    setError("");
    setFillItems([]);
    api<Item[]>(`/api/projects/${projectKey}/backlog`)
      .then(setBacklogItems)
      .catch(() => setBacklogItems([]));
  }, [open, projectKey]);

  async function doPull(estimates: Record<string, { v: string; u: string }>) {
    setFillItems([]);
    setError("");
    try {
      for (const [itemId, e] of Object.entries(estimates)) {
        // 补录先落票(值非法由后端校验,错误浮出)
        await api(`/api/items/${itemId}`, {
          method: "PATCH",
          body: JSON.stringify({ estimateValue: e.v, estimateUnit: e.u }),
        });
      }
      for (const it of backlogItems ?? []) {
        if (!picked.has(it.itemId)) continue;
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

  function confirmPull() {
    setError("");
    // SRS 修订:选择控件不变;完成后检查勾选票,有缺预估 → 弹补录对话框列出全部需录入票
    const needing = (backlogItems ?? []).filter(
      (it) => picked.has(it.itemId) && it.estimateValue == null,
    );
    if (needing.length > 0) {
      setFillItems(
        needing.map(({ itemId, number, title }) => ({ itemId, number, title })),
      );
      return;
    }
    void doPull({});
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
        <Button variant="contained" onClick={confirmPull}>
          完成
        </Button>
      </DialogActions>
      <EstimateFillDialog
        open={fillItems.length > 0}
        items={fillItems}
        onCancel={() => setFillItems([])}
        onSubmit={(estimates) => void doPull(estimates)}
      />
    </Dialog>
  );
}
