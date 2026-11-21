"use client";

import { InputAdornment, Stack, TextField, Typography } from "@mui/material";
import SearchIcon from "@mui/icons-material/Search";
import { Dropdown } from "@/components/Dropdown";
import { ColumnConfigPopover } from "@/components/ColumnConfigPopover";
import { t } from "@/lib/texts";
import type { ColumnDef, Filters, Status } from "../shared";

/** 筛选 + 列配置(YPJ-47/28);查询/筛选/列态留 page 走受控,写路径经回调置 dirty。 */
export default function TableFilterBar({
  query,
  onQueryChange,
  filters,
  onFilterChange,
  statuses,
  assignees,
  columns,
  colOrder,
  cols,
  dirty,
  loaded,
  onToggleCol,
  onMoveCol,
  onResetCols,
}: {
  query: string;
  onQueryChange: (q: string) => void;
  filters: Filters;
  onFilterChange: (f: Partial<Filters>) => void;
  statuses: Status[];
  assignees: string[];
  columns: ColumnDef[];
  colOrder: string[];
  cols: Record<string, boolean>;
  dirty: boolean;
  loaded: boolean;
  onToggleCol: (id: string) => void;
  onMoveCol: (id: string, dir: "up" | "down") => void;
  onResetCols: () => void;
}) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ mb: 1 }}
      flexWrap="wrap"
      useFlexGap
      alignItems="center"
    >
      <TextField
        aria-label={t.view.searchPlaceholder}
        placeholder={t.view.searchPlaceholder}
        size="small"
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        sx={{ width: 220 }}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon fontSize="small" />
              </InputAdornment>
            ),
          },
        }}
      />
      <Dropdown
        ariaLabel={t.view.filterStatus}
        size="small"
        clearable
        value={filters.status}
        onChange={(v) => onFilterChange({ status: v })}
        sx={{ width: 130 }}
        placeholder={t.view.filterStatus}
        options={statuses.map((s) => ({ value: s.name, label: s.name }))}
      />
      <Dropdown
        ariaLabel={t.view.filterAssignee}
        size="small"
        clearable
        value={filters.assignee}
        onChange={(v) => onFilterChange({ assignee: v })}
        sx={{ width: 130 }}
        placeholder={t.view.filterAssignee}
        options={[...assignees, "__none__"].map((a) => ({
          value: a,
          label: a === "__none__" ? t.view.unassigned : a,
        }))}
      />
      <Dropdown
        ariaLabel={t.view.filterPriority}
        size="small"
        clearable
        value={filters.priority}
        onChange={(v) => onFilterChange({ priority: v })}
        sx={{ width: 130 }}
        placeholder={t.view.filterPriority}
        options={["P0", "P1", "P2", "P3"].map((p) => ({
          value: p,
          label: p,
        }))}
      />
      <ColumnConfigPopover
        disabled={!loaded}
        columns={colOrder.flatMap((id) => {
          const c = columns.find((x) => x.id === id);
          return c ? [{ ...c, locked: id === "title" }] : [];
        })}
        visible={(id) =>
          cols[id] ?? columns.find((c) => c.id === id)?.visible ?? true
        }
        onToggle={onToggleCol}
        onMove={onMoveCol}
        onReset={onResetCols}
      />
      {dirty && (
        <Typography variant="caption" color="warning.main">
          {t.view.dirty}
        </Typography>
      )}
    </Stack>
  );
}
