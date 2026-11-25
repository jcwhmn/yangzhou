"use client";
// YPJ-280:开工须有主 409 → 弹窗补指派,选人后调用方完成原目标状态迁移。
// 候选来源与 AssignPopover 同源(/candidates);判据在调用方(409 + message 含「指派」)。
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from "@mui/material";
import { candidateColor, VerdictLine } from "@/components/Verdict";
import { t } from "@/lib/texts";
import type { AssignCandidate } from "@/components/AssignPopover";

export function AssignBeforeMoveDialog({
  open,
  itemNumber,
  targetStatusName,
  candidates,
  onPick,
  onCancel,
}: {
  open: boolean;
  itemNumber: string;
  targetStatusName: string;
  candidates: AssignCandidate[] | null;
  onPick: (memberId: string) => void;
  onCancel: () => void;
}) {
  return (
    <Dialog open={open} onClose={onCancel} maxWidth="sm" fullWidth>
      <DialogTitle>
        {t.assignBeforeMove.title(itemNumber, targetStatusName)}
      </DialogTitle>
      <DialogContent>
        {candidates === null && <CircularProgress size={20} />}
        {candidates !== null && candidates.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            {t.assign.noCandidates}
          </Typography>
        )}
        {candidates?.map((c) => {
          const color = candidateColor(c);
          return (
            <Box
              key={c.memberId}
              data-testid="move-assign-cand"
              onClick={() => onPick(c.memberId)}
              sx={{
                borderLeft: "3px solid " + color,
                pl: 1,
                py: 0.5,
                cursor: "pointer",
                "&:hover": { bgcolor: "action.hover" },
              }}
            >
              <Stack direction="row" spacing={1} alignItems="center">
                <Chip size="small" label={`#${c.rank}`} />
                <Typography variant="body2">
                  {c.displayName}
                  {c.virtual ? t.assign.virtualSuffix : ""}
                </Typography>
                <Chip
                  size="small"
                  variant="outlined"
                  sx={{ color, borderColor: color }}
                  label={t.assign.gapSummary(c.missingCount, c.totalDelta)}
                />
              </Stack>
              {c.verdicts.map((v, i) => (
                <VerdictLine key={i} v={v} />
              ))}
            </Box>
          );
        })}
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>{t.assign.cancel}</Button>
      </DialogActions>
    </Dialog>
  );
}

export default AssignBeforeMoveDialog;
