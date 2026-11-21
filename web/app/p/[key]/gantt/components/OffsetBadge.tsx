"use client";

import { Typography } from "@mui/material";

/** 偏移徽标:仅 FINAL;On time 中性 / ±Nh / ±Nd,提前绿/延期红(SRS §11.1,YPJ-245)(YPJ-249 自 page.tsx 原样搬出,零语义变化) */
export function OffsetBadge({
  badge,
  left,
  number,
}: {
  badge: { label: string; color: string } | null;
  left: number;
  number: string;
}) {
  return (
    <>
      {badge !== null && (
        <Typography
          variant="caption"
          data-testid={`gantt-offset-${number}`}
          sx={{
            position: "absolute",
            left,
            top: 24,
            lineHeight: "12px",
            fontWeight: 600,
            color: badge.color,
            whiteSpace: "nowrap",
          }}
        >
          {badge.label}
        </Typography>
      )}
    </>
  );
}
