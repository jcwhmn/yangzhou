"use client";

import { Box, Button, Chip, Stack, Typography } from "@mui/material";
import { t } from "@/lib/texts";
import { type ReqRow } from "../types";

/** RequirementsTab(YPJ-251 自 page.tsx 搬出;reqOpen/RequirementsDialog 跨 tab 且常挂载,留 page) */
export function RequirementsTab({
  requirements,

  onRequestEdit,
}: {
  requirements: ReqRow[];

  onRequestEdit: () => void;
}) {
  return (
    <Box>
      <Typography variant="h6">{t.item.requirements}</Typography>

      {(requirements ?? []).length === 0 ? (
        <Typography color="text.secondary" variant="body2">
          (无需求——判定聚合在上方)
        </Typography>
      ) : (
        <Stack spacing={0.5}>
          {requirements.map((r, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="center">
              <Chip size="small" variant="outlined" label={r.attribute} />

              <Typography variant="body2">
                {r.minLevel ? `≥${r.minLevel}` : "在场即可"}
              </Typography>
            </Stack>
          ))}
        </Stack>
      )}

      <Button
        size="small"
        variant="outlined"
        onClick={onRequestEdit}
        sx={{ mt: 2 }}
      >
        编辑需求
      </Button>
    </Box>
  );
}
