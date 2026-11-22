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
import { useState } from "react";
import { type Candidate } from "../types";

/** AddMembersDialog(YPJ-252 自 page.tsx 搬出;V13-S3 拉入/退回,态留 page 拍板 4)。
 *  YPJ-216(spec 0164 途径④):行内预估补录 + 应用到全部;onApply 携带每票预估,页面先落预估再拉入。 */
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
  /** estimates:勾选票的补录预估({数值,单位});仅需补录的票(原无预估且填了值) */
  onApply: (estimates: Record<string, { v: string; u: string }>) => void;
}) {
  const [est, setEst] = useState<Record<string, { v: string; u: string }>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [allV, setAllV] = useState("");
  const [allU, setAllU] = useState("hour");

  const setRowEst = (id: string, patch: Partial<{ v: string; u: string }>) => {
    setEst((prev) => ({ ...prev, [id]: { ...prev[id], v: prev[id]?.v ?? "", u: prev[id]?.u ?? "hour", ...patch } }));
    setDirty((prev) => new Set(prev).add(id));
  };

  const applyAll = () => {
    if (!allV) return;
    setEst((prev) => {
      const next = { ...prev };
      for (const it of candidates) {
        if (dirty.has(it.itemId)) continue; // 已手动改过的行不覆盖
        next[it.itemId] = { v: allV, u: allU };
      }
      return next;
    });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>拉入 item</DialogTitle>
      <DialogContent dividers sx={{ maxHeight: 480 }}>
        <List dense disablePadding>
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
          {candidates.map((it) => {
            const e = est[it.itemId];
            return (
              <ListItem key={it.itemId} dense disableGutters sx={{ gap: 1 }}>
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
                {selected.has(it.itemId) && (
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
          {candidates.length === 0 && (
            <Typography variant="caption" color="text.secondary">
              项目里没有可拉入的非终态 item。
            </Typography>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button
          variant="contained"
          onClick={() =>
            onApply(
              Object.fromEntries(
                Object.entries(est).filter(([id, e]) => selected.has(id) && e.v),
              ),
            )
          }
          disabled={busy}
        >
          完成
        </Button>
      </DialogActions>
    </Dialog>
  );
}
