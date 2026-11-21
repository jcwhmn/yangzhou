"use client";

import { Button, Chip, Stack } from "@mui/material";
import { t } from "@/lib/texts";
import type { Filter } from "../types";

/** S7:assignee 过滤 chips + 拉入入口;sprint 范围由后端 board 端点决定(YPJ-29)。过滤态留 page(派生 filtered 亦在 page)。 */
export default function FilterChips({
  filter,
  onFilterChange,
  memberOptions,
  onOpenPull,
}: {
  filter: Filter;
  onFilterChange: (filter: Filter) => void;
  memberOptions: string[];
  onOpenPull: () => void;
}) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ mb: 2 }}
      flexWrap="wrap"
      useFlexGap
    >
      <Chip
        label={t.filter.all}
        size="small"
        color={filter === "all" ? "primary" : "default"}
        onClick={() => onFilterChange("all")}
      />
      <Chip
        label={t.filter.unassigned}
        size="small"
        color={filter === "unassigned" ? "primary" : "default"}
        onClick={() => onFilterChange("unassigned")}
      />
      <Chip
        label="⛔ 被阻塞"
        size="small"
        color={filter === "blocked" ? "primary" : "default"}
        onClick={() => onFilterChange("blocked")}
      />
      <Button size="small" variant="outlined" onClick={onOpenPull}>
        {t.board.pullItem}
      </Button>
      {memberOptions.map((name) => (
        <Chip
          key={name}
          label={`👤 ${name}`}
          size="small"
          color={filter === name ? "primary" : "default"}
          onClick={() => onFilterChange(name)}
        />
      ))}
    </Stack>
  );
}
