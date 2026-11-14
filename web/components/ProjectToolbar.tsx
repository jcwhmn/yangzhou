"use client";

// YPJ-187:项目统一顶栏——由 layout 渲染一次全页覆盖;右侧固定 工时/收藏/设置;
// 快速添加槽由 看板/backlog/level-1 组页 经 context 注册(其余页不显示)
import { Box, IconButton, Link as MuiLink } from "@mui/material";
import SettingsOutlinedIcon from "@mui/icons-material/SettingsOutlined";
import StarBorderIcon from "@mui/icons-material/StarBorder";
import StarIcon from "@mui/icons-material/Star";
import Link from "next/link";
import { createContext, createRef, useContext, type RefObject } from "react";
import { t } from "@/lib/texts";

// 快速添加槽:合格页(看板/backlog/level-1 组页)用 createPortal 把自己的表单渲染进顶栏;
// 表单状态留在页内(语义不搬),顶栏只供位置
export const ProjectToolbarContext = createContext<{
  slotRef: RefObject<HTMLDivElement | null>;
}>({ slotRef: createRef() });

export function useProjectToolbarSlot() {
  return useContext(ProjectToolbarContext);
}

export function ProjectToolbar({
  projectKey,
  fav,
  onToggleFav,
  onOpenSettings,
}: {
  projectKey: string;
  fav: boolean | null;
  onToggleFav: () => void;
  onOpenSettings: () => void;
}) {
  const { slotRef } = useProjectToolbarSlot();
  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 1,
        px: 3,
        py: 1,
        borderBottom: 1,
        borderColor: "divider",
      }}
    >
      <Box
        ref={slotRef}
        sx={{
          flex: 1,
          minWidth: 0,
          display: "flex",
          alignItems: "center",
          gap: 1,
        }}
      />
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
        <MuiLink
          component={Link}
          href={`/p/${projectKey}/time`}
          underline="hover"
          variant="body2"
        >
          {t.toolbar.time}
        </MuiLink>
        <IconButton
          size="small"
          aria-label="favorite"
          disabled={fav === null}
          onClick={onToggleFav}
        >
          {fav ? (
            <StarIcon fontSize="small" />
          ) : (
            <StarBorderIcon fontSize="small" />
          )}
        </IconButton>
        <IconButton
          size="small"
          aria-label={t.settings.title}
          onClick={onOpenSettings}
        >
          <SettingsOutlinedIcon fontSize="small" />
        </IconButton>
      </Box>
    </Box>
  );
}
