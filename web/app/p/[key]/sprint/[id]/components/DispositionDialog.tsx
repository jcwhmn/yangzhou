"use client";

import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { t } from "@/lib/texts";
import { type Disp } from "../types";

/** DispositionDialog(YPJ-252 自 page.tsx 搬出;0110 处置,disp 态留 page(动作群产出消费)拍板 4,行按钮/摘要派生内置) */
export function DispositionDialog({
  disp,

  sprintName,

  busy,

  onClose,

  onPick,

  onNewName,

  onToggleRow,

  onBacklogAll,

  onConfirm,
}: {
  disp: Disp | null;

  sprintName: string;

  busy: boolean;

  onClose: () => void;

  onPick: (sprintId: string) => void;

  onNewName: (newName: string) => void;

  onToggleRow: (idx: number) => void;

  onBacklogAll: () => void;

  onConfirm: () => void;
}) {
  function targetLabel(d: typeof disp): string {
    if (!d) return "";
    if (d.actives.length > 1 || d.planned.length > 1)
      return t.sprintPage.dispPickTitle;
    if (d.actives.length === 1) return `转入进行中的「${d.actives[0].name}」`;
    if (d.planned.length === 1)
      return `将激活计划中的「${d.planned[0].name}」并转入`;
    return `将新建组「${d.newName}」并激活,转入`;
  }
  // 摘要行用:目标组名(多选时取 pick,与 confirmDisposition 落库一致)
  function targetGroupName(d: NonNullable<typeof disp>): string {
    if (d.actives.length > 0)
      return (d.actives.find((s) => s.sprintId === d.pick) ?? d.actives[0])
        .name;
    if (d.planned.length > 0)
      return (d.planned.find((s) => s.sprintId === d.pick) ?? d.planned[0])
        .name;
    return d.newName;
  }

  return (
    <>
      {/* 0106:非终态成员处置——目标组决策树 + 逐行覆写回 Backlog(D2/D3);0107 行内显式按钮 + 提交前摘要(方案A,消除 checkbox 误勾事故) */}
      <Dialog open={disp !== null} maxWidth="sm" fullWidth>
        <DialogTitle>
          完成「{sprintName}」——{disp?.rows.length ?? 0} 个未完成 item 待处置
        </DialogTitle>
        <DialogContent>
          <Typography sx={{ mt: 1, mb: 1, fontWeight: 600 }}>
            {targetLabel(disp)}
          </Typography>
          {disp && (disp.actives.length > 1 || disp.planned.length > 1) && (
            <Stack spacing={1} sx={{ mb: 1 }}>
              {[...disp.actives, ...disp.planned].map((sp) => (
                <Button
                  key={sp.sprintId}
                  variant={disp.pick === sp.sprintId ? "contained" : "outlined"}
                  onClick={() => onPick(sp.sprintId)}
                >
                  {sp.name}
                </Button>
              ))}
            </Stack>
          )}
          {disp && disp.actives.length === 0 && disp.planned.length === 0 && (
            <TextField
              label={t.board.newGroupName}
              value={disp.newName}
              onChange={(e) => onNewName(e.target.value)}
              fullWidth
              sx={{ mb: 1 }}
            />
          )}
          {disp?.rows.map((r, idx) => (
            <Box
              key={r.itemId}
              sx={{ display: "flex", alignItems: "center", gap: 1 }}
            >
              <Box
                sx={{
                  flex: 1,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  color: r.toBacklog ? "warning.dark" : "text.primary",
                }}
              >
                {r.title}
              </Box>
              {r.toBacklog && (
                <Typography
                  variant="body2"
                  color="warning.dark"
                  fontWeight={600}
                >
                  {t.sprintPage.dispRowMarked}
                </Typography>
              )}
              <Button
                size="small"
                variant={r.toBacklog ? "contained" : "text"}
                color={r.toBacklog ? "warning" : "inherit"}
                aria-label={`${r.toBacklog ? t.sprintPage.dispRowUndo : t.sprintPage.dispRowBacklog} ${r.title}`}
                onClick={() => onToggleRow(idx)}
              >
                {r.toBacklog
                  ? t.sprintPage.dispRowUndo
                  : t.sprintPage.dispRowBacklog}
              </Button>
            </Box>
          ))}
          {disp && disp.rows.length > 0 && (
            <Typography
              variant="body2"
              sx={{ mt: 1 }}
              color={
                disp.rows.some((r) => r.toBacklog)
                  ? "warning.dark"
                  : "text.secondary"
              }
            >
              {`${t.sprintPage.dispSummaryMove}「${targetGroupName(disp)}」 ${disp.rows.filter((r) => !r.toBacklog).length} ${t.sprintPage.dispSummaryBack} ${disp.rows.filter((r) => r.toBacklog).length} ${t.sprintPage.dispSummaryTail}`}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          {/* 0110:取消|进 Backlog|进「组名」三钮(用户拍板一次弹出) */}
          <Button onClick={onClose} disabled={busy}>
            "取消"
          </Button>
          <Button onClick={onBacklogAll} disabled={busy}>
            {t.sprintPage.dispGoBacklog}
          </Button>
          <Button variant="contained" onClick={onConfirm} disabled={busy}>
            {disp && `进「${targetGroupName(disp)}」`}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
