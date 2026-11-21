"use client";

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  Typography,
} from "@mui/material";
import { t } from "@/lib/texts";

/** 命名 Dialog(save 首存 / saveAs 另存共用);viewName/saving/nameConflict/error 受控,onSave 上行 saveCurrent。 */
export default function ViewNameDialog({
  open,
  mode,
  viewName,
  onNameChange,
  nameConflict,
  saving,
  error,
  onClose,
  onSave,
}: {
  open: boolean;
  mode: "save" | "saveAs";
  viewName: string;
  onNameChange: (name: string) => void;
  nameConflict: boolean;
  saving: boolean;
  error: string;
  onClose: () => void;
  onSave: () => void;
}) {
  return (
    <Dialog open={open} onClose={onClose}>
      <DialogTitle>
        {mode === "saveAs" ? t.view.saveAs : t.view.save}
      </DialogTitle>
      <DialogContent>
        {open && error && (
          <Typography color="error" variant="body2">
            {error}
          </Typography>
        )}
        <TextField
          autoFocus
          fullWidth
          size="small"
          label={t.view.viewName}
          value={viewName}
          error={nameConflict}
          helperText={nameConflict ? t.view.nameConflict : undefined}
          onChange={(e) => onNameChange(e.target.value)}
          sx={{ mt: 1 }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.assign.cancel}</Button>
        <Button
          variant="contained"
          disabled={!viewName.trim() || nameConflict || saving}
          onClick={onSave}
        >
          {t.view.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
