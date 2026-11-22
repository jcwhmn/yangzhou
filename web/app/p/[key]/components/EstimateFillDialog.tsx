"use client";

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  TextField,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";

/** YPJ-216(spec 0164 途径③④):选择完成后的补录对话框——列出所有缺预估的勾选票逐行录入。
 *  每行预填项目缺省预估(设置→中档;未设置回退内置 5 day),不输入直接提交也可(2026-11-24 用户反馈)。
 *  取消 → 不拉入。 */
export default function EstimateFillDialog({
  open,
  projectKey,
  items,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  projectKey: string;
  /** 需补录的票(勾选中且原无预估) */
  items: { itemId: string; number: number | string; title: string }[];
  onCancel: () => void;
  /** estimates:仅需补录的票(原无预估且填了值);提交时全部行已填 */
  onSubmit: (estimates: Record<string, { v: string; u: string }>) => void;
}) {
  const [est, setEst] = useState<Record<string, { v: string; u: string }>>({});
  const [allV, setAllV] = useState("");
  const [allU, setAllU] = useState("hour");

  useEffect(() => {
    if (!open) return;
    // 项目缺省预估(中档)预填:未设置 → 内置 5 day(SRS 4.4 同源)
    api<{
      defaultEstimateValue: string | null;
      defaultEstimateUnit: string | null;
    }>(`/api/projects/${projectKey}`)
      .then((p) => {
        const v = p.defaultEstimateValue ?? "5";
        const u = p.defaultEstimateUnit ?? "day";
        setAllV(v);
        setAllU(u);
        setEst(Object.fromEntries(items.map((it) => [it.itemId, { v, u }])));
      })
      .catch(() => {
        setAllV("5");
        setAllU("day");
        setEst(
          Object.fromEntries(
            items.map((it) => [it.itemId, { v: "5", u: "day" }]),
          ),
        );
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  const allFilled =
    items.length > 0 &&
    items.every((it) => est[it.itemId]?.v && Number(est[it.itemId]!.v) > 0);

  const applyAll = () => {
    if (!allV) return;
    setEst((prev) => {
      const next = { ...prev };
      for (const it of items) next[it.itemId] = { v: allV, u: allU };
      return next;
    });
  };

  return (
    <Dialog open={open} onClose={onCancel} maxWidth="sm" fullWidth>
      <DialogTitle>补录预估</DialogTitle>
      <DialogContent dividers sx={{ maxHeight: 480 }}>
        <List dense disablePadding>
          <ListItem dense disableGutters sx={{ gap: 1 }}>
            <TextField
              size="small"
              label="预估(应用到全部)"
              placeholder={allV || "5"}
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
          {items.map((it) => (
            <ListItem key={it.itemId} dense disableGutters sx={{ gap: 1 }}>
              <ListItemText primary={`${it.number} · ${it.title}`} />
              <TextField
                size="small"
                label="预估"
                placeholder={allV || "5"}
                value={est[it.itemId]?.v ?? ""}
                onChange={(ev) =>
                  setEst((prev) => ({
                    ...prev,
                    [it.itemId]: {
                      v: ev.target.value,
                      u: prev[it.itemId]?.u ?? allU,
                    },
                  }))
                }
                sx={{ width: 90 }}
              />
              <TextField
                size="small"
                select
                label="单位"
                value={est[it.itemId]?.u ?? allU}
                onChange={(ev) =>
                  setEst((prev) => ({
                    ...prev,
                    [it.itemId]: {
                      v: prev[it.itemId]?.v ?? allV,
                      u: ev.target.value,
                    },
                  }))
                }
                sx={{ width: 80 }}
              >
                {["hour", "day", "week"].map((u) => (
                  <MenuItem key={u} value={u}>
                    {u}
                  </MenuItem>
                ))}
              </TextField>
            </ListItem>
          ))}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>取消</Button>
        <Button
          variant="contained"
          disabled={!allFilled}
          onClick={() =>
            onSubmit(
              Object.fromEntries(
                items.map((it) => [it.itemId, est[it.itemId]!]),
              ),
            )
          }
        >
          提交
        </Button>
      </DialogActions>
    </Dialog>
  );
}
