"use client";

import {
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
import type { SprintLite } from "../types";

/** 0105 S2/S2m/S3:创建前置确认——激活并放入/新建并激活;取消=放弃创建(D6)。pick/name 态留 page 走受控,onConfirm 上行 confirmLaunch。 */
export default function LaunchDialog({
  open,
  onClose,
  mode,
  sprints,
  pick,
  onPick,
  name,
  onNameChange,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  mode: "activate" | "create";
  sprints: SprintLite[];
  pick: string;
  onPick: (sprintId: string) => void;
  name: string;
  onNameChange: (name: string) => void;
  onConfirm: () => void;
}) {
  const planned = sprints.filter((s) => s.status === "planned");
  return (
    <Dialog open={open} onClose={onClose}>
      <DialogTitle>
        {mode === "activate" && planned.length > 1
          ? t.board.launchPickTitle
          : t.board.launchTitle}
      </DialogTitle>
      <DialogContent>
        {mode === "create" ? (
          <TextField
            label={t.board.newGroupName}
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
            autoFocus
            fullWidth
            sx={{ mt: 1 }}
          />
        ) : planned.length > 1 ? (
          <Stack spacing={1} sx={{ mt: 1 }}>
            {planned.map((sp) => (
              <Button
                key={sp.sprintId}
                variant={pick === sp.sprintId ? "contained" : "outlined"}
                onClick={() => onPick(sp.sprintId)}
              >
                {sp.name}
              </Button>
            ))}
          </Stack>
        ) : (
          <Typography>
            {t.board.launchBody.replace("{name}", planned[0]?.name ?? "")}
          </Typography>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.board.launchCancel}</Button>
        <Button variant="contained" onClick={onConfirm}>
          {mode === "create"
            ? t.board.launchCreateConfirm
            : t.board.launchConfirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
