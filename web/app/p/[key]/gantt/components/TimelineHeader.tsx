"use client";

import { Box, Typography } from "@mui/material";
import { type HeaderRow } from "../types";

/** 多粒度刻度头(YPJ-249 自 page.tsx 原样搬出,零语义变化):月/周/日/时 2–3 行,自上而下粗→细 */
export function TimelineHeader({ headerRows }: { headerRows: HeaderRow[] }) {
  return (
    <>
      {headerRows.map((row) => (
        <Box
          key={row.gran}
          data-testid={`gantt-row-${row.gran}`}
          sx={{
            position: "relative",
            height: 24,
            borderBottom: "1px solid",
            borderColor: "divider",
            bgcolor: "background.default",
          }}
        >
          {row.ticks.map((tk) => (
            <Typography
              key={tk.key}
              variant="caption"
              color="text.secondary"
              sx={{
                position: "absolute",
                left: tk.x + 4,
                top: 2,
                whiteSpace: "nowrap",
              }}
            >
              {tk.label}
            </Typography>
          ))}
        </Box>
      ))}
    </>
  );
}
