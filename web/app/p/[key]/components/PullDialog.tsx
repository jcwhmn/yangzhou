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

/** 拉入弹窗:open 时拉 backlog、picked 局部;确认逐条 PUT 上板(原 page openPull/confirmPull 自包含随迁),完成后 onPulled 上行重拉。
 *  YPJ-216(spec 0164 途径③,2026-11-24 SRS 修订):选择控件保持原样不动;确认时勾选票有缺预估 →
 *  先弹 EstimateFillDialog 补录,提交后继续拉入。 */
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

  async function doPull() {
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

  async function confirmPull() {
    // YPJ-216 SRS 修订:完成后检查勾选票,有缺预估 → 弹补录对话框列出全部需录入票,提交后继续
    const needing = (backlogItems ?? []).filter(
      (it) => picked.has(it.itemId) && it.estimateValue == null,
    );
    if (needing.length > 0) {
      setFillItems(
        needing.map(({ itemId, number, title }) => ({ itemId, number, title })),
      );
      return;
    }
    await doPull();
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
      <EstimateFillDialog
        open={fillItems.length > 0}
        items={fillItems}
        onCancel={() => setFillItems([])}
        onSubmit={async (estimates) => {
          setFillItems([]);
          try {
            for (const [itemId, e] of Object.entries(estimates)) {
              // 补录先落票(值非法由后端校验,错误浮出)
              await api(`/api/items/${itemId}`, {
                method: "PATCH",
                body: JSON.stringify({ estimateValue: e.v, estimateUnit: e.u }),
              });
            }
          } catch (err) {
            setError(err instanceof Error ? err.message : "补录失败");
            return;
          }
          await doPull();
        }}
      />
    </Dialog>
  );
}
