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

/** 全量 item DTO(含 onBoard;看板端点不含该标记,列表用全量端点自筛) */
type ItemRow = Item & { onBoard: boolean };

/** 拉入弹窗:候选 = 已在看板 item(预选中,取消勾选=退回)+ backlog item;确认逐条 PUT board-membership。
 *  YPJ-216(spec 0164 途径③,2026-11-24 SRS 修订):完成后勾选中缺预估的新拉入票 → EstimateFillDialog 补录后继续。 */
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
  const [items, setItems] = useState<ItemRow[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [error, setError] = useState(""); // YPJ-216:守卫 409 要浮出(无预估拉入不再静默吞,2026-11-23 手工实锤)
  // 项目设置:必预估时才需补录(非必填直接拉入,2026-11-24 用户反馈)
  const [estimateRequired, setEstimateRequired] = useState(true);
  // 补录阶段:非空 = 有缺预估的新拉入票,先弹补录框(列出全部需录入票)
  const [fillItems, setFillItems] = useState<
    { itemId: string; number: number | string; title: string }[]
  >([]);

  useEffect(() => {
    if (!open) return;
    setError("");
    setFillItems([]);
    api<{ estimateRequired: boolean }>(`/api/projects/${projectKey}`)
      .then((p) => setEstimateRequired(p.estimateRequired))
      .catch(() => setEstimateRequired(true));
    api<{ items: Item[] }>(`/api/projects/${projectKey}/board`)
      .then((r) => {
        const onBoard = r.items.map((it) => ({ ...it, onBoard: true }));
        setPicked(new Set(onBoard.map((it) => it.itemId)));
        return api<Item[]>(`/api/projects/${projectKey}/backlog`).then((bl) =>
          setItems([
            ...onBoard,
            ...bl.map((it) => ({ ...it, onBoard: false })),
          ] as ItemRow[]),
        );
      })
      .catch(() => setItems([]));
  }, [open, projectKey]);

  async function doPull() {
    setError("");
    try {
      for (const it of items ?? []) {
        const now = picked.has(it.itemId);
        if (it.onBoard === now) continue; // 勾选状态未变化的票不发请求
        await api(`/api/items/${it.itemId}/board-membership`, {
          method: "PUT",
          body: JSON.stringify({ onBoard: now }),
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
    // YPJ-216 SRS 修订:必预估项目才检查新拉入票的缺预估 → 弹补录框;非必填直接拉入(2026-11-24 用户反馈)
    const needing = estimateRequired
      ? (items ?? []).filter(
          (it) =>
            !it.onBoard && picked.has(it.itemId) && it.estimateValue == null,
        )
      : [];
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
          {[...(items ?? [])]
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
          {(items ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t.board.pullEmpty}
            </Typography>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" onClick={confirmPull}>
          {t.board.pullConfirm}
        </Button>
      </DialogActions>
      <EstimateFillDialog
        open={fillItems.length > 0}
        projectKey={projectKey}
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
