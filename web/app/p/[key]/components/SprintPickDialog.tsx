"use client";

import {
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  Stack,
} from "@mui/material";
import type { SprintLite } from "../types";

/** S1m:多进行中组 → 用户选(归属是用户意图,不代选);纯展示,onPick 上行走 createInGroup。 */
export default function SprintPickDialog({
  open,
  onClose,
  activeSprints,
  onPick,
}: {
  open: boolean;
  onClose: () => void;
  activeSprints: SprintLite[];
  onPick: (sprintId: string) => void;
}) {
  return (
    <Dialog open={open} onClose={onClose}>
      <DialogTitle>放入哪个组</DialogTitle>
      <DialogContent>
        <Stack spacing={1} sx={{ mt: 1 }}>
          {activeSprints.map((sp) => (
            <Button
              key={sp.sprintId}
              variant="outlined"
              onClick={() => onPick(sp.sprintId)}
            >
              {sp.name}
            </Button>
          ))}
        </Stack>
      </DialogContent>
    </Dialog>
  );
}
