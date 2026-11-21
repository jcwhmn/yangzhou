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
import { type Candidate } from "../types";

/** AddMembersDialog(YPJ-252 自 page.tsx 搬出;V13-S3 拉入/退回,态留 page 拍板 4) */
export function AddMembersDialog({
  open,

  candidates,

  selected,

  busy,

  onToggle,

  onClose,

  onApply,
}: {
  open: boolean;

  candidates: Candidate[];

  selected: Set<string>;

  busy: boolean;

  onToggle: (itemId: string) => void;

  onClose: () => void;

  onApply: () => void;
}) {
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
        <Button variant="contained" onClick={onApply} disabled={busy}>
          完成
        </Button>
      </DialogActions>
    </Dialog>
  );
}
