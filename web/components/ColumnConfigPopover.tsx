"use client";

import { useState } from "react";
import {
  Button,
  Checkbox,
  Chip,
  IconButton,
  Popover,
  Stack,
  Typography,
} from "@mui/material";
import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import ViewColumnIcon from "@mui/icons-material/ViewColumn";
import { t } from "@/lib/texts";

/** YPJ-28/47 列配置(spec 0045)+ YPJ-131 列顺序(spec 0091 §6):列表按当前显示顺序传入,locked 行恒显不可勾;编号钉首位不入列。 */
export function ColumnConfigPopover({
  columns,
  visible,
  onToggle,
  onMove,
  onReset,
  disabled,
}: {
  columns: { id: string; label: string; locked?: boolean }[];
  visible: (id: string) => boolean;
  onToggle: (id: string) => void;
  onMove: (id: string, dir: "up" | "down") => void;
  onReset: () => void;
  disabled?: boolean;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const open = anchor !== null;
  const activeCount = columns.filter((c) => visible(c.id)).length;

  return (
    <>
      <Chip
        size="small"
        icon={<ViewColumnIcon />}
        label={`${t.view.colConfig} ${activeCount}/${columns.length}`}
        clickable
        disabled={disabled}
        color={open ? "primary" : "default"}
        onClick={(e) => setAnchor(e.currentTarget)}
      />
      <Popover
        open={open}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
      >
        <Stack sx={{ p: 2, minWidth: 220 }} spacing={0.5}>
          <Typography variant="caption" color="text.secondary">
            {t.view.colConfig}
          </Typography>
          {columns.map((c, i) => (
            <Stack key={c.id} direction="row" alignItems="center" spacing={0.5}>
              <Checkbox
                size="small"
                checked={c.locked ? true : visible(c.id)}
                disabled={c.locked}
                onChange={() => onToggle(c.id)}
                sx={{ my: -0.5 }}
              />
              <Typography
                variant="body2"
                sx={{ cursor: c.locked ? "default" : "pointer", flex: 1 }}
                onClick={() => !c.locked && onToggle(c.id)}
              >
                {c.label}
              </Typography>
              <IconButton
                size="small"
                aria-label={t.view.colUp}
                disabled={i === 0}
                onClick={() => onMove(c.id, "up")}
              >
                <ArrowUpwardIcon sx={{ fontSize: 16 }} />
              </IconButton>
              <IconButton
                size="small"
                aria-label={t.view.colDown}
                disabled={i === columns.length - 1}
                onClick={() => onMove(c.id, "down")}
              >
                <ArrowDownwardIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Stack>
          ))}
          <Button size="small" onClick={onReset} sx={{ mt: 1 }}>
            {t.view.resetCols}
          </Button>
        </Stack>
      </Popover>
    </>
  );
}
