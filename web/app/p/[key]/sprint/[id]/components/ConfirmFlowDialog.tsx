"use client";

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
} from "@mui/material";
import { t } from "@/lib/texts";
import { type Item, type Sprint, type Status } from "../types";

/** ConfirmFlowDialog(YPJ-252 自 page.tsx 搬出;开始/重开/完成确认,文案派生内置,动作回调上行) */
export function ConfirmFlowDialog({
  confirmFlow,

  groupId,

  typeName,

  sprint,

  groups,

  items,

  statuses,

  onClose,

  onConfirm,
}: {
  confirmFlow: null | "start" | "complete" | "reopen";

  groupId: string;

  typeName: string | null;

  sprint: Sprint;

  groups: Sprint[];

  items: Item[] | null;

  statuses: Status[];

  onClose: () => void;

  onConfirm: () => void;
}) {
  return (
    <Dialog
      open={confirmFlow !== null}
      onClose={onClose}
      maxWidth="xs"
      fullWidth
    >
      <DialogTitle>
        {confirmFlow === "start"
          ? `开始 ${typeName ?? sprint.typeKey}`
          : confirmFlow === "reopen"
            ? "重开组"
            : `完成 ${typeName ?? sprint.typeKey}`}
      </DialogTitle>
      <DialogContent>
        <Typography>
          {confirmFlow === "start"
            ? `项目已有进行中的 ${typeName ?? sprint.typeKey}「${groups.find((s) => s.typeKey === sprint.typeKey && s.status === "active" && s.sprintId !== groupId)?.name ?? ""}」,允许同时存在多个进行中。仍要开始本 ${typeName ?? sprint.typeKey}?`
            : confirmFlow === "reopen"
              ? t.sprintPage.reopenConfirm
              : `完成后,${(items ?? []).filter((it) => !(statuses.find((st) => st.name === it.status)?.statusType === "FINAL")).length} 个未完成 item 将进入处置(选定去向);已完成 item 保留在本页历史。`}
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" onClick={onConfirm}>
          {confirmFlow === "start"
            ? "仍要开始"
            : confirmFlow === "reopen"
              ? "确认重开"
              : "确认完成"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
