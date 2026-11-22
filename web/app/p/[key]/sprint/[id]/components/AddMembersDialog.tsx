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
import EstimateFillDialog from "../../../components/EstimateFillDialog";
import { type Candidate } from "../types";

/** AddMembersDialog(YPJ-252 自 page.tsx 搬出;V13-S3 拉入/退回,态留 page 拍板 4)。
 *  YPJ-216(spec 0164 途径④,2026-11-24 SRS 修订):选择控件保持原样不动(本组成员仍预选中);
 *  确认时勾选票有缺预估 → 先弹 EstimateFillDialog 补录,onApply 携带每票预估,页面先落预估再拉入。 */
export function AddMembersDialog({
  open,

  projectKey,

  candidates,

  selected,

  busy,

  onToggle,

  onClose,

  onApply,
}: {
  open: boolean;

  projectKey: string;

  candidates: Candidate[];

  selected: Set<string>;

  busy: boolean;

  onToggle: (itemId: string) => void;

  onClose: () => void;

  /** 原版为 () => void;YPJ-216 可选携带补录预估(页面 applyMembership 先落预估再拉入) */
  onApply: (estimates?: Record<string, { v: string; u: string }>) => void;
}) {
  // 补录阶段:非空 = 勾选票中有缺预估,先弹补录框
  const [fillItems, setFillItems] = useState<
    { itemId: string; number: number | string; title: string }[]
  >([]);
  // 项目设置:必预估时才需补录(非必填直接拉入,2026-11-24 用户反馈)
  const [estimateRequired, setEstimateRequired] = useState(true);

  useEffect(() => {
    if (!open) return;
    api<{ estimateRequired: boolean }>(`/api/projects/${projectKey}`)
      .then((p) => setEstimateRequired(p.estimateRequired))
      .catch(() => setEstimateRequired(true));
  }, [open, projectKey]);

  function confirmApply() {
    const needing = estimateRequired
      ? candidates.filter(
          (it) => selected.has(it.itemId) && it.estimateValue == null,
        )
      : [];
    if (needing.length > 0) {
      setFillItems(
        needing.map(({ itemId, number, title }) => ({ itemId, number, title })),
      );
      return;
    }
    onApply();
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>拉入 item</DialogTitle>
      <DialogContent dividers sx={{ maxHeight: 480 }}>
        <List dense disablePadding>
          {candidates.map((it) => (
            <ListItem key={it.itemId} dense disableGutters>
              <Checkbox
                size="small"
                checked={selected.has(it.itemId)}
                onChange={() => onToggle(it.itemId)}
              />
              <ListItemText
                primary={`${it.number} · ${it.title}`}
                secondary={[it.status, it.assignee ? `👤 ${it.assignee}` : null]
                  .filter(Boolean)
                  .join(" · ")}
              />
            </ListItem>
          ))}
          {candidates.length === 0 && (
            <Typography variant="caption" color="text.secondary">
              项目里没有可拉入的非终态 item。
            </Typography>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" onClick={confirmApply} disabled={busy}>
          完成
        </Button>
      </DialogActions>
      <EstimateFillDialog
        open={fillItems.length > 0}
        projectKey={projectKey}
        items={fillItems}
        onCancel={() => setFillItems([])}
        onSubmit={(estimates) => {
          setFillItems([]);
          onApply(estimates);
        }}
      />
    </Dialog>
  );
}
