"use client";

import { Box, Button, Chip, Stack, Typography } from "@mui/material";
import Link from "next/link";
import {
  type GanttRow,
  type HeaderRow,
  EST_UNIT_SHORT,
  TITLE_W_DEFAULT,
} from "../types";

/** 左侧标题面板(YPJ-249 自 page.tsx 原样搬出,零语义变化):单元格/分割条/面板组合。
 * renderTitleCell→TitleCell:key 上移到组件调用;page 的 key 参数改名 projectKey(href 字符串不变)。 */
export function TitleCell({
  r,
  projectKey,
  hasKid,
  toggle,
  collapsed,
  tintOf,
}: {
  r: GanttRow;
  projectKey: string;
  hasKid: (r: GanttRow) => boolean;
  toggle: (itemId: string) => void;
  collapsed: Set<string>;
  tintOf: (r: GanttRow) => string | null;
}) {
  const isParent = hasKid(r);
  const tint = tintOf(r);
  return (
    <Box
      key={r.itemId}
      sx={{
        borderBottom: "1px solid",
        borderColor: "divider",
        minHeight: 44,
        display: "flex",
        alignItems: "center",
        bgcolor: tint ?? "transparent", // spec 0165:状态底色(父行 null=透明)
        "&:hover": { bgcolor: "action.hover" },
      }}
    >
      <Stack
        direction="row"
        spacing={0.5}
        alignItems="center"
        sx={{ pl: 1 + r.depth * 2, py: 0.5 }}
      >
        {hasKid(r) && (
          <Button
            size="small"
            onClick={() => toggle(r.itemId)}
            sx={{ minWidth: 0, px: 0.5 }}
          >
            {collapsed.has(r.itemId) ? "▶" : "▼"}
          </Button>
        )}
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题可点进详情;自然宽不截断,列内横滚(YPJ-236);原生 title 悬停显全名(spec 0165 §3.5);父行字重 600(spec 0165 §3.4) */}
        <Link
          href={`/p/${projectKey}/i/${r.itemId}?from=gantt`}
          style={{ minWidth: 0 }}
        >
          {/* component=div:inline span 吃不到盒模型属性,nowrap 文字会画出盒叠压 chip(YPJ-230) */}
          <Typography
            component="div"
            data-testid={`gantt-title-${r.number}`}
            variant="caption"
            noWrap
            title={r.title}
            sx={{
              color: "text.primary",
              textDecoration: "underline",
              fontWeight: isParent ? 600 : 400,
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
        {r.estimateValue !== null && (
          <Chip
            data-testid={`gantt-est-${r.number}`}
            label={`${r.estimateValue}${EST_UNIT_SHORT[r.estimateUnit ?? ""] ?? r.estimateUnit ?? ""}`}
            size="small"
            variant="outlined"
            sx={{ height: 18 }}
          />
        )}
        {r.blocked && (
          <Chip
            data-testid={`gantt-blocked-${r.number}`}
            label="🔗 blocked"
            size="small"
            color="warning"
            sx={{ height: 18 }}
          />
        )}
      </Stack>
    </Box>
  );
}

/** 237 分割条:骑在标题列右边线上的透明热区,hover/拖拽/键盘焦点显 info.main 竖线 */
export function Splitter({
  titleW,
  splitting,
  onSplitMouseDown,
  persistTitleW,
}: {
  titleW: number;
  splitting: boolean;
  onSplitMouseDown: (e: React.MouseEvent<HTMLDivElement>) => void;
  persistTitleW: (w: number) => void;
}) {
  return (
    <Box
      data-testid="gantt-splitter"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整标题列宽度"
      tabIndex={0}
      onMouseDown={onSplitMouseDown}
      onDoubleClick={() => persistTitleW(TITLE_W_DEFAULT)}
      onKeyDown={(e) => {
        if (e.key === "ArrowLeft") {
          e.preventDefault();
          persistTitleW(titleW - 16);
        } else if (e.key === "ArrowRight") {
          e.preventDefault();
          persistTitleW(titleW + 16);
        }
      }}
      sx={{
        width: 9,
        flexShrink: 0,
        ml: "-5px", // 骑上右边线;不用 sx 数字(被 theme spacing 放大,实锤 -35px)
        position: "relative",
        zIndex: 1,
        cursor: "col-resize",
        "&::after": {
          content: '""',
          position: "absolute",
          top: 0,
          bottom: 0,
          left: 3.5,
          width: 2,
          bgcolor: splitting ? "info.main" : "transparent",
        },
        "&:hover::after, &:focus-visible::after": {
          bgcolor: "info.main",
        },
      }}
    />
  );
}

/** 标题面板:表头占位与甘特区刻度行同高对齐 + 主树/已完成组标题 */
export function TitleColumn({
  titleW,
  headerRows,
  mainRows,
  completedRows,
  showCompleted,
  onToggleCompleted,
  collapsed,
  toggle,
  hasKid,
  tintOf,
  projectKey,
}: {
  titleW: number;
  headerRows: HeaderRow[];
  mainRows: GanttRow[];
  completedRows: GanttRow[];
  showCompleted: boolean;
  onToggleCompleted: () => void;
  collapsed: Set<string>;
  toggle: (itemId: string) => void;
  hasKid: (r: GanttRow) => boolean;
  tintOf: (r: GanttRow) => string | null;
  projectKey: string;
}) {
  return (
    <Box
      data-testid="gantt-titlepane"
      sx={{
        width: titleW,
        flexShrink: 0,
        overflowX: "auto",
        overflowY: "hidden",
        bgcolor: "grey.50",
        borderRight: "1px solid",
        borderColor: "divider",
      }}
    >
      <Box sx={{ width: "max-content", minWidth: "100%" }}>
        {/* 表头占位:与甘特区刻度行同高同边框,保证行对齐 */}
        {headerRows.map((row) => (
          <Box
            key={row.gran}
            sx={{
              height: 24,
              borderBottom: "1px solid",
              borderColor: "divider",
            }}
          />
        ))}
        {mainRows.map((r) => (
          <TitleCell
            key={r.itemId}
            r={r}
            projectKey={projectKey}
            hasKid={hasKid}
            toggle={toggle}
            collapsed={collapsed}
            tintOf={tintOf}
          />
        ))}
        {completedRows.length > 0 && (
          <Box
            sx={{
              borderBottom: "1px solid",
              borderColor: "divider",
              minHeight: 44,
              display: "flex",
              alignItems: "center",
            }}
          >
            <Button
              size="small"
              data-testid="gantt-completed-group"
              onClick={onToggleCompleted}
              sx={{ pl: 1 }}
            >
              {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
            </Button>
          </Box>
        )}
        {showCompleted &&
          completedRows.map((r) => (
            <TitleCell
              key={r.itemId}
              r={r}
              projectKey={projectKey}
              hasKid={hasKid}
              toggle={toggle}
              collapsed={collapsed}
              tintOf={tintOf}
            />
          ))}
      </Box>
    </Box>
  );
}
