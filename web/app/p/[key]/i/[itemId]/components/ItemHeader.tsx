// YPJ-257 页面重构(纯结构):详情页头部行(返回链接 + 票号/信号/指派/状态/导航/关票理由 chips)自 page 下沉
"use client";

import { Chip, IconButton, Stack, Tooltip, Typography } from "@mui/material";

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";

import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";

import Link from "next/link";

import { t } from "@/lib/texts";

import { SignalChip } from "@/components/Verdict";

import { AssignPopover } from "@/components/AssignPopover";

import { Dropdown } from "@/components/Dropdown";

import type { CloseReason } from "@/lib/closeReason";

import type { Feasibility, Item, Status } from "../types";

type Nav = {
  prev: string | null;
  next: string | null;
  index: number;
  total: number;
} | null;

export function ItemHeader({
  back,
  item,
  keyStr,
  itemId,
  feasibility,
  statuses,
  nav,
  onAssign,
  onMoveStatus,
  onLoad,
  onEditCloseReason,
}: {
  back: { href: string; label: string };
  item: Item;
  keyStr: string;
  itemId: string;
  feasibility: Feasibility | null;
  statuses: Status[];
  nav: Nav;
  onAssign: (memberId: string | null) => void;
  onMoveStatus: (statusName: string) => void;
  onLoad: () => void;
  onEditCloseReason: () => void;
}) {
  return (
    <>
      <Link href={back.href} style={{ textDecoration: "none" }}>
        <Typography variant="body2" color="primary" gutterBottom>
          {back.label}
        </Typography>
      </Link>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        {item.parentNumber && (
          <Link
            href={`/p/${keyStr}/i/${item.parentItemId}`}
            style={{ textDecoration: "none" }}
          >
            <Typography
              variant="body2"
              color="primary"
              sx={{ whiteSpace: "nowrap" }}
            >
              {item.parentNumber} ›
            </Typography>
          </Link>
        )}
        <Chip label={item.number} />
        {feasibility && <SignalChip signal={feasibility.signal} />}
        {item.assignee ? (
          <AssignPopover
            itemId={itemId}
            assignee={item.assignee}
            onChanged={onLoad}
          >
            {(onClick) => (
              <Chip
                size="small"
                label={`👤 ${item.assignee}`}
                onClick={onClick}
                onDelete={() => onAssign(null)}
              />
            )}
          </AssignPopover>
        ) : (
          <AssignPopover itemId={itemId} assignee={null} onChanged={onLoad}>
            {(onClick) => (
              <Chip
                size="small"
                variant="outlined"
                label={t.assign.open}
                onClick={onClick}
                sx={{ opacity: 0.55 }}
              />
            )}
          </AssignPopover>
        )}
        <Dropdown
          ariaLabel="item 状态"
          size="small"
          value={item.status}
          onChange={(v) => onMoveStatus(v)}
          sx={{ minWidth: 160 }}
          options={statuses.map((s) => ({ value: s.name, label: s.name }))}
        />
        {nav && nav.total > 1 && (
          <Stack direction="row" spacing={0.5} alignItems="center">
            {nav.prev && (
              <Tooltip title={t.item.prevTip}>
                <IconButton
                  size="small"
                  aria-label="previous item"
                  href={`/p/${keyStr}/i/${nav.prev}`}
                >
                  <ChevronLeftOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ whiteSpace: "nowrap" }}
            >
              {nav.index + 1} / {nav.total}
            </Typography>
            {nav.next && (
              <Tooltip title={t.item.nextTip}>
                <IconButton
                  size="small"
                  aria-label="next item"
                  href={`/p/${keyStr}/i/${nav.next}`}
                >
                  <ChevronRightOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
          </Stack>
        )}
        {statuses.find((s) => s.name === item.status)?.statusType ===
          "FINAL" && (
          <Tooltip title={t.closeReason.editTitle}>
            <Chip
              size="small"
              onClick={onEditCloseReason}
              color={item.closeReason === "done" ? "default" : "warning"}
              label={
                (item.closeReason &&
                  t.closeReason[item.closeReason as CloseReason]) ??
                item.closeReason
              }
            />
          </Tooltip>
        )}
      </Stack>
    </>
  );
}
