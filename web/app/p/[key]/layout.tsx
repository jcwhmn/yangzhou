"use client";

import { Box, Divider, List, ListItemButton, ListItemText, ListSubheader, Typography } from "@mui/material";
import Link from "next/link";
import { usePathname, useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type Sprint = { sprintId: string; name: string; status: string };

/**
 * V12-S2 项目侧边栏壳(spec 0010,裁决 3 最小 IA):
 * 看板(全部 items)+ Sprint 段(Backlog 派生桶入口 + 各 sprint,active→planned→completed)。
 * 现有甘特/表格/工时链接留在各页头部不动;Sprint 落地页在 S3。
 */
export default function ProjectLayout({ children }: { children: React.ReactNode }) {
  const { key } = useParams<{ key: string }>();
  const pathname = usePathname();
  const [sprints, setSprints] = useState<Sprint[] | null>(null);

  useEffect(() => {
    api<Sprint[]>(`/api/projects/${key}/sprints`).then(setSprints).catch(() => setSprints([]));
  }, [key]);

  const rank = { active: 0, planned: 1, completed: 2 } as const;
  const sorted = [...(sprints ?? [])].sort((a, b) => (rank[a.status as keyof typeof rank] ?? 9) - (rank[b.status as keyof typeof rank] ?? 9));

  const itemSx = (selected: boolean) => ({ selected, py: 0.5 });

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <Box component="nav" sx={{ width: 210, flexShrink: 0, bgcolor: "grey.50" }}>
        <List dense disablePadding sx={{ py: 2 }}>
          <ListItemButton component={Link} href={`/p/${key}`} sx={itemSx(pathname === `/p/${key}`)}>
            <ListItemText primary={t.projectNav.board} />
          </ListItemButton>
          <Divider sx={{ my: 1 }} />
          <ListSubheader disableSticky sx={{ bgcolor: "transparent" }}>
            {t.projectNav.sprints}
          </ListSubheader>
          <ListItemButton component={Link} href={`/p/${key}/backlog`} sx={itemSx(pathname === `/p/${key}/backlog`)}>
            <ListItemText primary={t.projectNav.backlog} />
          </ListItemButton>
          {(sprints ?? []).length === 0 && sprints !== null && (
            <ListItemButton disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={t.projectNav.empty}
                primaryTypographyProps={{ variant: "caption", color: "text.secondary" }}
              />
            </ListItemButton>
          )}
          {sorted.map((s) => (
            <ListItemButton
              key={s.sprintId}
              component={Link}
              href={`/p/${key}/sprint/${s.sprintId}`}
              sx={itemSx(pathname === `/p/${key}/sprint/${s.sprintId}`)}
            >
              <ListItemText
                primary={s.name}
                secondary={t.projectNav.sprintStatus[s.status as keyof typeof t.projectNav.sprintStatus] ?? s.status}
              />
            </ListItemButton>
          ))}
        </List>
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 0 }}>
        {children}
      </Box>
    </Box>
  );
}
