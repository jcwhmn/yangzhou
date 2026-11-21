"use client";

import { Box, Stack, Typography } from "@mui/material";
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
      <Typography variant="subtitle2" sx={{ px: 1, py: 0.5 }}>
        {status.icon ? `${status.icon} ` : ""}
        {status.name}({list.length}){status.statusType === "FINAL" && " [终态]"}
      </Typography>
      <Stack spacing={1}>
        {list.length === 0 && (
          <Typography variant="caption" color="text.secondary" sx={{ px: 1 }}>
            (空)
          </Typography>
        )}
        {list.map((it) => (
          <BoardCard
            key={it.itemId}
            it={it}
            projectKey={projectKey}
            removable={status.statusType !== "FINAL"}
            onRemoved={onRemoved}
            onAssigneeChanged={onAssigneeChanged}
          />
        ))}
      </Stack>
    </Box>
  );
}
