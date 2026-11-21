"use client";

import {
  Chip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { t } from "@/lib/texts";
import { AssignPopover } from "@/components/AssignPopover";
import { type Item } from "../types";

/** SprintItemsTable(YPJ-252 自 page.tsx 搬出;指派变更经 onAssigneeChanged 回 page 重拉) */
export function SprintItemsTable({
  projectKey,

  groupId,

  items,

  onAssigneeChanged,
}: {
  projectKey: string;

  groupId: string;

  items: Item[];

  onAssigneeChanged: () => void;
}) {
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            {["编号", "标题", "优先级", "状态", "负责人", "截止"].map((c) => (
              <TableCell key={c}>{c}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((it) => (
            <TableRow key={it.itemId} hover>
              <TableCell>
                <Link
                  href={`/p/${projectKey}/i/${it.itemId}?from=sprint-${groupId}`}
                >
                  <Typography variant="body2" sx={{ whiteSpace: "nowrap" }}>
                    {(it.parentNumber ? it.parentNumber + " › " : "") +
                      it.number}
                  </Typography>
                </Link>
              </TableCell>
              <TableCell>
                <Link
                  href={`/p/${projectKey}/i/${it.itemId}?from=sprint-${groupId}`}
                >
                  <Typography variant="body2">{it.title}</Typography>
                </Link>
              </TableCell>
              <TableCell>{it.priority ?? "—"}</TableCell>
              <TableCell>{it.status}</TableCell>
              <TableCell>
                <AssignPopover
                  itemId={it.itemId}
                  assignee={it.assignee}
                  onChanged={onAssigneeChanged}
                >
                  {(onClick) => (
                    <Chip
                      size="small"
                      variant="outlined"
                      label={it.assignee ? `👤 ${it.assignee}` : t.assign.none}
                      onClick={onClick}
                      sx={it.assignee ? undefined : { opacity: 0.55 }}
                    />
                  )}
                </AssignPopover>
              </TableCell>
              <TableCell>{it.dueDate ?? "—"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
