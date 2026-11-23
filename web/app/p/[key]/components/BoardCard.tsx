"use client";

import {
  Card,
  CardContent,
  Chip,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import UndoOutlined from "@mui/icons-material/UndoOutlined";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { estimateText } from "@/lib/estimate";
import { fmtDate } from "@/lib/dates";
import { AssignPopover } from "@/components/AssignPopover";
import type { Item } from "../types";

/** 看板卡:chips 行/标题/due+est/requirements;移出内置(原 page setBoardItem(false) 随迁,onRemoved 上行),drag dataTransfer 键名逐字节保留。 */
export default function BoardCard({
  it,
  projectKey,
  removable,
  onRemoved,
  onAssigneeChanged,
}: {
  it: Item;
  projectKey: string;
  removable: boolean;
  onRemoved: () => void | Promise<void>;
  onAssigneeChanged: () => void | Promise<void>;
}) {
  const router = useRouter();
  return (
    <Card
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData("text/plain", it.itemId);
        e.dataTransfer.effectAllowed = "move";
      }}
      data-board-item={it.itemId}
      onClick={() => router.push(`/p/${projectKey}/i/${it.itemId}`)}
      sx={{
        cursor: "pointer",
        borderLeft: "4px solid " + (it.assigneeColor ?? "#9e9e9e"),
        transition: "box-shadow .15s, border-color .15s",
        "&:hover": {
          boxShadow: 6,
          borderColor: "primary.main",
        },
      }}
    >
      <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} alignItems="center">
            {it.parentNumber && (
              <Link
                href={`/p/${projectKey}/i/${it.parentItemId}`}
                style={{ textDecoration: "none" }}
              >
                <Typography
                  variant="caption"
                  color="primary"
                  sx={{ whiteSpace: "nowrap" }}
                >
                  {it.parentNumber} ›
                </Typography>
              </Link>
            )}
            <Chip label={it.number} size="small" variant="outlined" />
            {it.feasSignal && it.feasSignal !== "GREEN" && (
              <Chip
                size="small"
                label={t.signal[it.feasSignal]}
                color={it.feasSignal === "RED" ? "error" : "warning"}
              />
            )}
            {it.blocked && (
              <Chip
                size="small"
                color="error"
                variant="outlined"
                label="⛔ 被阻塞"
              />
            )}
            {it.sprintName && (
              <Chip size="small" variant="outlined" label={it.sprintName} />
            )}
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
            {removable && (
              <Tooltip title={t.board.remove}>
                <IconButton
                  size="small"
                  aria-label={t.board.remove}
                  onClick={(e) => {
                    e.stopPropagation();
                    api(`/api/items/${it.itemId}/board-membership`, {
                      method: "PUT",
                      body: JSON.stringify({ onBoard: false }),
                    }).then(onRemoved);
                  }}
                  sx={{ alignSelf: "center", mr: -0.5 }}
                >
                  <UndoOutlined fontSize="inherit" />
                </IconButton>
              </Tooltip>
            )}
          </Stack>
          <Link
            href={`/p/${projectKey}/i/${it.itemId}`}
            style={{ textDecoration: "none", color: "inherit" }}
          >
            <Typography variant="body2" sx={{ wordBreak: "break-word" }}>
              {it.title}
            </Typography>
          </Link>
          {(it.dueDate ||
            it.overdue ||
            it.dueSoon ||
            it.estimateValue != null) && (
            <Typography
              variant="caption"
              sx={{
                color: it.overdue
                  ? "error.main"
                  : it.dueSoon
                    ? "warning.main"
                    : "text.secondary",
                fontWeight: it.overdue || it.dueSoon ? 700 : 400,
              }}
            >
              {it.estimateValue != null && (
                <Typography
                  component="span"
                  variant="caption"
                  sx={{
                    color: "text.secondary",
                    fontWeight: 400,
                  }}
                >
                  {estimateText(it.estimateValue, it.estimateUnit)}
                  {it.dueDate ? " · " : ""}
                </Typography>
              )}
              {it.dueDate &&
                (it.overdue ? "⚠ 超期 " : it.dueSoon ? "⏳ 将到期 " : "📅 ")}
              {it.dueDate ? fmtDate(it.dueDate) : ""}
            </Typography>
          )}
          {it.requirements.length > 0 && (
            <Typography variant="caption" color="text.secondary">
              {it.requirements
                .map((r) =>
                  r.minLevel ? `${r.attribute}≥${r.minLevel}` : r.attribute,
                )
                .join(" · ")}
            </Typography>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
