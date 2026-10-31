"use client";

import { useState } from "react";
import {
  Button,
  Checkbox,
  Chip,
  Popover,
  Stack,
  Typography,
} from "@mui/material";
import ViewColumnIcon from "@mui/icons-material/ViewColumn";
import { t } from "@/lib/texts";

/** YPJ-28/47:列配置收进 Popover 多选面板(spec 0045);编号/标题恒显,不在可关列表。 */
export function ColumnConfigPopover({
  columns,
  visible,
  onToggle,
  onReset,
}: {
  columns: { id: string; label: string }[];
  visible: (id: string) => boolean;
  onToggle: (id: string) => void;
  onReset: () => void;
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
        color={open ? "primary" : "default"}
        onClick={(e) => setAnchor(e.currentTarget)}
      />
      <Popover
        open={open}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
      >
        <Stack sx={{ p: 2, minWidth: 200 }} spacing={0.5}>
          <Typography variant="caption" color="text.secondary">
            {t.view.colConfig}
          </Typography>
          {columns.map((c) => (
            <Stack key={c.id} direction="row" alignItems="center" spacing={0.5}>
              <Checkbox
                size="small"
                checked={visible(c.id)}
                onChange={() => onToggle(c.id)}
                sx={{ my: -0.5 }}
              />
              <Typography
                variant="body2"
                sx={{ cursor: "pointer" }}
                onClick={() => onToggle(c.id)}
              >
                {c.label}
              </Typography>
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
