"use client";

import {
  Chip,
  Link,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  Typography,
} from "@mui/material";
import { AssignPopover } from "@/components/AssignPopover";
import { Stack } from "@mui/material";
import { t } from "@/lib/texts";
import type { CloseReason } from "@/lib/closeReason";
import { estimateText } from "@/lib/estimate";
import { PAGE_SIZES, type ColumnDef, type Item, type SortKey } from "../shared";

/** 表格:表头多栏排序标签(单击循环/Shift 替换,回调上行)+ 13 列单元格分发 + 分页(YPJ-125 兜底在 page 的 safePage)。 */
export default function ItemsTable({
  visibleCols,
  paged,
  sorts,
  onToggleSort,
  projectKey,
  onAssigneeChanged,
  count,
  page,
  onPageChange,
  rowsPerPage,
  onRowsPerPageChange,
}: {
  visibleCols: ColumnDef[];
  paged: Item[];
  sorts: SortKey[];
  onToggleSort: (field: string, replace: boolean) => void;
  projectKey: string;
  onAssigneeChanged: () => void | Promise<void>;
  count: number;
  page: number;
  onPageChange: (page: number) => void;
  rowsPerPage: number;
  onRowsPerPageChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <>
      <TableContainer sx={{ maxHeight: "calc(100vh - 280px)" }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              {visibleCols.map((c) => {
                const idx = sorts.findIndex((k) => k.field === c.id);
                return (
                  <TableCell
                    key={c.id}
                    sortDirection={idx >= 0 ? sorts[idx].dir : false}
                  >
                    <TableSortLabel
                      active={idx >= 0}
                      title={t.view.sortHint}
                      direction={idx >= 0 ? sorts[idx].dir : "asc"}
                      onClick={(e) => onToggleSort(c.id, e.shiftKey)}
                      sx={{
                        // MUI 默认非激活列 hover 才见箭头,可发现性差;常显(半透明,激活实色)
                        ".MuiTableSortLabel-icon": { opacity: 0.3 },
                        "&:hover .MuiTableSortLabel-icon": { opacity: 0.6 },
                        "&.Mui-active .MuiTableSortLabel-icon": {
                          opacity: 1,
                        },
                      }}
                    >
                      {c.label}
                      {sorts.length > 1 && idx >= 0 && (
                        <Typography
                          component="span"
                          variant="caption"
                          sx={{ ml: 0.5, opacity: 0.6 }}
                        >
                          {idx + 1}
                        </Typography>
                      )}
                    </TableSortLabel>
                  </TableCell>
                );
              })}
            </TableRow>
          </TableHead>
          <TableBody>
            {paged.map((it) => (
              <TableRow key={it.itemId} hover>
                {visibleCols.map((c) => (
                  <TableCell key={c.id}>
                    {c.id === "number" ? (
                      <Link href={`/p/${projectKey}/i/${it.itemId}?from=table`}>
                        <Typography
                          variant="body2"
                          sx={{ whiteSpace: "nowrap" }}
                        >
                          {(it.parentNumber ? it.parentNumber + " › " : "") +
                            it.number}
                        </Typography>
                      </Link>
                    ) : c.id === "title" ? (
                      <Link href={`/p/${projectKey}/i/${it.itemId}?from=table`}>
                        <Typography variant="body2">{it.title}</Typography>
                      </Link>
                    ) : c.id === "status" ? (
                      it.closeReason && it.closeReason !== "done" ? (
                        // YPJ-275:非完成关闭标识(spec 0167 §7.3)
                        <Stack
                          direction="row"
                          spacing={0.5}
                          alignItems="center"
                        >
                          <Typography variant="body2">{it.status}</Typography>
                          <Chip
                            size="small"
                            color="warning"
                            variant="outlined"
                            label={t.closeReason[it.closeReason as CloseReason]}
                          />
                        </Stack>
                      ) : (
                        it.status
                      )
                    ) : c.id === "priority" ? (
                      (it.priority ?? "")
                    ) : c.id === "estimate" ? (
                      estimateText(it.estimateValue, it.estimateUnit)
                    ) : c.id === "timesheet" ? (
                      it.totalMinutes > 0 ? (
                        t.time.fmt(it.totalMinutes)
                      ) : (
                        "—"
                      )
                    ) : c.id === "assignee" ? (
                      <AssignPopover
                        itemId={it.itemId}
                        assignee={it.assignee}
                        onChanged={onAssigneeChanged}
                      >
                        {(onClick) => (
                          <Chip
                            size="small"
                            variant="outlined"
                            label={
                              it.assignee ? `👤 ${it.assignee}` : t.assign.none
                            }
                            onClick={onClick}
                            sx={it.assignee ? undefined : { opacity: 0.55 }}
                          />
                        )}
                      </AssignPopover>
                    ) : c.id === "startDate" ? (
                      (it.startDate ?? "")
                    ) : c.id === "dueDate" ? (
                      <span
                        style={{
                          color: it.overdue
                            ? "red"
                            : it.dueSoon
                              ? "orange"
                              : "inherit",
                        }}
                      >
                        {it.dueDate ?? ""}
                      </span>
                    ) : c.id === "overdue" ? (
                      it.overdue ? (
                        "是"
                      ) : (
                        "否"
                      )
                    ) : c.id === "feasSignal" ? (
                      it.feasSignal ? (
                        t.signal[it.feasSignal]
                      ) : (
                        ""
                      )
                    ) : c.id === "sprint" ? (
                      (it.sprintName ?? (
                        <Typography variant="body2" sx={{ opacity: 0.5 }}>
                          {t.view.backlog}
                        </Typography>
                      ))
                    ) : c.id === "blocked" ? (
                      it.blocked ? (
                        "⛔"
                      ) : (
                        ""
                      )
                    ) : (
                      ""
                    )}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        rowsPerPageOptions={PAGE_SIZES}
        count={count}
        page={page}
        onPageChange={(_, p) => onPageChange(p)}
        rowsPerPage={rowsPerPage}
        onRowsPerPageChange={onRowsPerPageChange}
        labelRowsPerPage={t.view.perPage}
        labelDisplayedRows={({ from, to, count }) => `${from}-${to} / ${count}`}
      />
    </>
  );
}
