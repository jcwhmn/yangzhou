// YPJ-257 页面重构(纯结构):详情页头部行自 page 下沉
// YPJ-276(spec 0173):瘦身为纯导航——返回/父票链/票号/桶内 prev-next;状态/负责人/信号迁右栏 ItemSidebar
"use client";

import { Chip, IconButton, Stack, Tooltip, Typography } from "@mui/material";

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";

import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";

import Link from "next/link";

import { t } from "@/lib/texts";

import type { Item } from "../types";

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
  nav,
}: {
  back: { href: string; label: string };
  item: Item;
  keyStr: string;
  nav: Nav;
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
      </Stack>
    </>
  );
}
