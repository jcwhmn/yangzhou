"use client";

import { Box, Button, Chip, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { type GanttRow } from "../types";

/** 未排期折叠组(YPJ-249 自 page.tsx 原样搬出,零语义变化;RowTitle 仅此处消费,随之收编) */
export function UnscheduledGroup({
  unscheduled,
  showUnscheduled,
  onToggle,
  projectKey,
}: {
  unscheduled: GanttRow[];
  showUnscheduled: boolean;
  onToggle: () => void;
  projectKey: string;
}) {
  function RowTitle({ r }: { r: GanttRow }) {
    return (
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题列可点进详情(条形区另有整块隐形覆盖链接,YPJ-220 反馈补) */}
        <Link href={`/p/${projectKey}/i/${r.itemId}?from=gantt`}>
          <Typography
            variant="caption"
            color="text.primary"
            noWrap
            sx={{
              textDecoration: "underline",
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
      </Stack>
    );
  }
  return (
    <>
      {unscheduled.length > 0 && (
        <Box sx={{ mb: 2 }}>
          <Button size="small" variant="outlined" onClick={onToggle}>
            {showUnscheduled ? "▼" : "▶"} 未排期({unscheduled.length})
          </Button>
          {showUnscheduled &&
            unscheduled.map((r) => (
              <Box key={r.itemId} sx={{ pl: 2 + r.depth * 2, mt: 0.5 }}>
                <RowTitle r={r} />
              </Box>
            ))}
        </Box>
      )}
    </>
  );
}
