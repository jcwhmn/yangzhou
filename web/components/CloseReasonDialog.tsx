"use client";

// YPJ-275(spec 0167 §7.1):关闭理由弹窗——转终态(源状态 requireCloseReason=true)与详情页改理由共用。
// 下拉默认 done;选非 done 展开备注框(≤2000);回车确认、Esc 取消;失败原地报错不关窗(由父层控 open)。
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from "@mui/material";
import { useEffect, useState } from "react";
import { Dropdown } from "@/components/Dropdown";
import { t } from "@/lib/texts";
import { CLOSE_REASONS, type CloseReason } from "@/lib/closeReason";

const MAX_NOTE = 2000;

export default function CloseReasonDialog({
  open,
  title,
  initialReason = "done",
  initialNote = "",
  error = "",
  confirmLabel,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  title: string;
  /** 详情页改理由时预填现值;转终态恒默认 done */
  initialReason?: CloseReason;
  initialNote?: string;
  /** PATCH 失败信息(原地报错不关窗) */
  error?: string;
  /** 缺省 = 转终态语义「确认关闭」;详情页改理由传「保存」 */
  confirmLabel?: string;
  onCancel: () => void;
  onConfirm: (reason: CloseReason, note: string | null) => void;
}) {
  const [reason, setReason] = useState<CloseReason>("done");
  const [note, setNote] = useState("");

  useEffect(() => {
    if (!open) return;
    setReason(initialReason);
    setNote(initialNote);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const confirm = () => onConfirm(reason, reason === "done" ? null : note);

  return (
    <Dialog
      open={open}
      onClose={onCancel}
      maxWidth="xs"
      fullWidth
      onKeyDown={(e) => {
        // 回车确认(备注框内回车换行除外);Esc 走 Dialog 默认 onCancel
        if (e.key === "Enter" && !(e.target instanceof HTMLTextAreaElement)) {
          e.preventDefault();
          confirm();
        }
      }}
    >
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Dropdown
            ariaLabel={t.closeReason.label}
            label={t.closeReason.label}
            value={reason}
            onChange={(v) => {
              setReason(v as CloseReason);
              if (v === "done") setNote(""); // spec 7.1:切回 done 收起并清内容
            }}
            fullWidth
            options={CLOSE_REASONS.map((r) => ({
              value: r,
              label: t.closeReason[r],
            }))}
          />
          {reason !== "done" && (
            <TextField
              label={t.closeReason.note}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              multiline
              minRows={2}
              slotProps={{
                htmlInput: { maxLength: MAX_NOTE },
              }}
              fullWidth
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>{t.closeReason.cancel}</Button>
        <Button variant="contained" onClick={confirm}>
          {confirmLabel ?? t.closeReason.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
