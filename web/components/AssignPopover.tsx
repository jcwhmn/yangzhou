"use client";
// V16-1:分配入口上浮——assignee 显示位即可点入口,弹出候选 Popover(点外部/Esc 收起)。
// 未足项确认 Dialog 与详情页同一语义(hasUnmet 单一出处);candidateColor 见 Verdict.tsx。
import { useState, type ReactNode } from "react";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Popover,
  Stack,
  Typography,
} from "@mui/material";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { VerdictLine, candidateColor, type Signal, type Verdict } from "@/components/Verdict";

export type AssignCandidate = {
  rank: number;
  memberId: string;
  displayName: string;
  virtual: boolean;
  signal: Signal;
  missingCount: number;
  totalDelta: number;
  verdicts: Verdict[];
};

/** 未足项 = 缺门,或缺级/未评级判定(详情页指派给我与各 surface 共用此判定) */
export const hasUnmet = (c: Pick<AssignCandidate, "missingCount" | "verdicts">) =>
  c.missingCount > 0 || c.verdicts.some((v) => v.kind === "gap" || v.kind === "missing" || v.kind === "unrated");

export function AssignPopover({
  itemId,
  assignee,
  onChanged,
  children,
}: {
  itemId: string;
  /** 当前负责人显示名(用于「当前」标注;surface 手上只有名字,按名匹配) */
  assignee: string | null;
  onChanged?: () => void;
  children: (onClick: (e: React.MouseEvent<HTMLElement>) => void) => ReactNode;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [cands, setCands] = useState<AssignCandidate[] | null>(null);
  const [confirmCand, setConfirmCand] = useState<AssignCandidate | null>(null);

  function open(e: React.MouseEvent<HTMLElement>) {
    // sprint 卡片外层是 Link、看板卡片 onClick 是路由跳转:吞掉冒泡与默认行为
    e.preventDefault();
    e.stopPropagation();
    setAnchor(e.currentTarget);
    setCands(null);
    api<AssignCandidate[]>(`/api/items/${itemId}/candidates`)
      .then(setCands)
      .catch(() => setCands([]));
  }
  const close = () => setAnchor(null);

  async function tryAssign(c: AssignCandidate) {
    if (hasUnmet(c)) setConfirmCand(c);
    else await assign(c.memberId);
  }

  async function assign(memberId: string) {
    await api(`/api/items/${itemId}/assignee`, { method: "PUT", body: JSON.stringify({ assigneeItemId: memberId }) });
    setConfirmCand(null);
    close();
    onChanged?.();
  }

  return (
    <>
      {children(open)}
      <Popover
        open={anchor !== null}
        anchorEl={anchor}
        onClose={close}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        transformOrigin={{ vertical: "top", horizontal: "left" }}
        // React portal 事件沿组件树冒泡:不切开,内层「指派」点击会冒到看板卡片的路由 onClick
        slotProps={{ paper: { onClick: (e: React.MouseEvent) => e.stopPropagation() } }}
      >
        <Stack sx={{ p: 1.5, minWidth: 320, maxWidth: 400, maxHeight: 480, overflowY: "auto" }} spacing={1}>
          <Typography variant="subtitle2">{t.assign.whoTitle}</Typography>
          {cands === null && (
            <Typography variant="body2" color="text.secondary">
              {t.assign.loading}
            </Typography>
          )}
          {cands !== null && cands.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t.assign.noCandidates}
            </Typography>
          )}
          {cands?.map((c) => {
            const current = c.displayName === assignee;
            const color = candidateColor(c);
            return (
              <Box
                key={c.memberId}
                data-testid="cand-row"
                sx={{ borderLeft: "3px solid " + color, pl: 1, py: 0.5, opacity: current ? 0.66 : 1 }}
              >
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip size="small" label={`#${c.rank}`} />
                  <Typography variant="body2">
                    {c.displayName}
                    {c.virtual ? t.assign.virtualSuffix : ""}
                  </Typography>
                  {current && (
                    <Chip size="small" color="primary" variant="outlined" label={t.assign.current} />
                  )}
                  <Chip
                    size="small"
                    variant="outlined"
                    sx={{ color, borderColor: color }}
                    label={t.assign.gapSummary(c.missingCount, c.totalDelta)}
                  />
                  <Button size="small" variant="contained" sx={{ ml: "auto" }} onClick={() => tryAssign(c)}>
                    {t.assign.assignBtn}
                  </Button>
                </Stack>
                {c.verdicts.map((v, i) => (
                  <VerdictLine key={i} v={v} />
                ))}
              </Box>
            );
          })}
        </Stack>
      </Popover>
      <Dialog
        open={confirmCand !== null}
        onClose={() => setConfirmCand(null)}
        // 同 Popover:Dialog 也 portal,阻断向 surface 卡片的冒泡
        slotProps={{ paper: { onClick: (e: React.MouseEvent) => e.stopPropagation() } }}
      >
        <DialogTitle>{t.assign.confirmTitle}</DialogTitle>
        <DialogContent>
          <Typography>
            {confirmCand?.displayName}:{t.assignDialog.unmetWarning}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmCand(null)}>{t.assign.cancel}</Button>
          <Button
            color="warning"
            variant="contained"
            onClick={() => {
              if (confirmCand) void assign(confirmCand.memberId);
            }}
          >
            {t.assign.confirmAnyway}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
