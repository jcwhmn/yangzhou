"use client";

import { Box, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import SortOutlined from "@mui/icons-material/SortOutlined";
import type { Item, Status } from "../types";
import BoardCard from "./BoardCard";

/** 看板列:drag 三 handler 由 page 逐列闭包下发(dataTransfer 键名/moveTo 零改动);dragOver 高亮态留 page。 */
export default function BoardColumn({
  status,
  list,
  dragOver,
  onDragOver,
  onDragLeave,
  onDrop,
  projectKey,
  onRemoved,
  onAssigneeChanged,
  sorting,
  onToggleSort,
  onMoveArrow,
}: {
  status: Status;
  list: Item[];
  dragOver: string | null;
  onDragOver: (e: React.DragEvent) => void;
  onDragLeave: () => void;
  onDrop: (e: React.DragEvent) => void;
  projectKey: string;
  onRemoved: () => void | Promise<void>;
  onAssigneeChanged: () => void | Promise<void>;
  sorting: boolean;
  onToggleSort: () => void;
  onMoveArrow: (itemId: string, dir: -1 | 1) => void;
}) {
  return (
    <Box
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
      sx={{
        minWidth: 280,
        minHeight: 300,
        p: 1,
        borderRadius: 2,
        bgcolor:
          dragOver === status.statusId ? "action.hover" : "background.default",
        border: "1px dashed",
        borderColor: dragOver === status.statusId ? "primary.main" : "divider",
      }}
    >
      <Stack direction="row" alignItems="center" sx={{ px: 1, py: 0.5 }}>
        <Typography variant="subtitle2" sx={{ flex: 1 }}>
          {status.icon ? `${status.icon} ` : ""}
          {status.name}({list.length})
          {status.statusType === "FINAL" && " [终态]"}
        </Typography>
        {/* YPJ-238:排序模式开关——点后卡片出上下箭头,再点关闭 */}
        <Tooltip title={sorting ? "退出排序" : "排序"}>
          <IconButton
            size="small"
            aria-label={sorting ? "退出排序" : "排序"}
            onClick={onToggleSort}
            color={sorting ? "primary" : "default"}
          >
            <SortOutlined fontSize="inherit" />
          </IconButton>
        </Tooltip>
      </Stack>
      <Stack spacing={1}>
        {list.length === 0 && (
          <Typography variant="caption" color="text.secondary" sx={{ px: 1 }}>
            (空)
          </Typography>
        )}
        {list.map((it, i) => (
          <BoardCard
            key={it.itemId}
            it={it}
            projectKey={projectKey}
            removable={status.statusType !== "FINAL"}
            onRemoved={onRemoved}
            onAssigneeChanged={onAssigneeChanged}
            onUp={
              sorting && i > 0 ? () => onMoveArrow(it.itemId, -1) : undefined
            }
            onDown={
              sorting && i < list.length - 1
                ? () => onMoveArrow(it.itemId, 1)
                : undefined
            }
          />
        ))}
      </Stack>
    </Box>
  );
}
