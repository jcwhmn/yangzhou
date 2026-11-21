"use client";

import { Box, Button, Stack, Typography } from "@mui/material";
import { t } from "@/lib/texts";
import { VerdictLine } from "@/components/Verdict";
import { type Feasibility } from "../types";

/** FeasibilityTab(YPJ-251 自 page.tsx 搬出;reqOpen 跨 tab2/tab3 留 page,回调上行) */
export function FeasibilityTab({
  feasibility,

  onRequestEdit,
}: {
  feasibility: Feasibility;

  onRequestEdit: () => void;
}) {
  return (
    <Box sx={{ mt: 3 }}>
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography variant="h6">{t.item.verdicts}</Typography>

        <Button size="small" variant="outlined" onClick={onRequestEdit}>
          编辑需求
        </Button>
      </Stack>

      {feasibility.verdicts.length === 0 && (
        <Typography color="text.secondary">(无需求)</Typography>
      )}

      {feasibility.verdicts.map((v, i) => (
        <VerdictLine key={i} v={v} />
      ))}

      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        缺门 {feasibility.missingCount} · 总差距 {feasibility.totalDelta} 级
      </Typography>
    </Box>
  );
}
