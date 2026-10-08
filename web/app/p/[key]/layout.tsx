"use client";

import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Divider, IconButton, List, ListItemButton, ListItemText, ListSubheader, Stack, TextField, Typography } from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import Link from "next/link";
import { usePathname, useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, API_BASE } from "@/lib/api";
import { t } from "@/lib/texts";
import { MilestoneManager, type Milestone } from "@/components/MilestoneManager";
import { ReleaseManager, type Release } from "@/components/ReleaseManager";
import { Download as DownloadIcon, GitHub as GitHubIcon, Group as GroupIcon, Tune as TuneIcon } from "@mui/icons-material";
import type { Signal } from "@/components/Verdict";
import { WorkflowEditor } from "@/components/WorkflowEditor";
import { ProjectMembersPanel } from "@/components/ProjectMembersPanel";
import { GithubSettingsPanel } from "@/components/GithubSettingsPanel";

type Sprint = { sprintId: string; name: string; status: string };
type NavItem = { itemId: string; number: string; title: string; type: string; parentItemId: string | null; feasSignal: Signal | null };

/**
 * V12-S2 项目侧边栏壳(spec 0010,裁决 3 最小 IA):
 * 看板(全部 items)+ Sprint 段(Backlog 派生桶入口 + 各 sprint,active→planned→completed)。
 * 现有甘特/表格/工时链接留在各页头部不动;Sprint 落地页在 S3。
 */
export default function ProjectLayout({ children }: { children: React.ReactNode }) {
  const { key } = useParams<{ key: string }>();
  const pathname = usePathname();
  const [sprints, setSprints] = useState<Sprint[] | null>(null);
  const [items, setItems] = useState<NavItem[]>([]);
  const [milestones, setMilestones] = useState<Milestone[]>([]);
  const [msOpen, setMsOpen] = useState(false);
  const [releases, setReleases] = useState<Release[]>([]);
  const [relOpen, setRelOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [error, setError] = useState("");
  const [wfOpen, setWfOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  const [ghOpen, setGhOpen] = useState(false);

  const loadSprints = () => {
    // CI 冷 JVM 偶发连接重置(status -1):失败隔 1s 重试一次
    api<Sprint[]>(`/api/projects/${key}/sprints`)
      .catch(() => new Promise<Sprint[]>((res) => setTimeout(() => res(api<Sprint[]>(`/api/projects/${key}/sprints`)), 1000)))
      .then(setSprints)
      .catch(() => setSprints([]));
  };

  const loadSide = () => {
    api<NavItem[]>(`/api/projects/${key}/items`).then(setItems).catch(() => setItems([]));
    api<Milestone[]>(`/api/projects/${key}/milestones`).then(setMilestones).catch(() => setMilestones([]));
    api<Release[]>(`/api/projects/${key}/releases`).then(setReleases).catch(() => setReleases([]));
  };

  useEffect(() => {
    loadSprints();
    loadSide();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  async function createSprint() {
    try {
      await api(`/api/projects/${key}/sprints`, {
        method: "POST",
        body: JSON.stringify({ name, startDate: startDate || null, endDate: endDate || null }),
      });
      setCreateOpen(false);
      setName(""); setStartDate(""); setEndDate(""); setError("");
      loadSprints();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    }
  }

  const rank = { active: 0, planned: 1, completed: 2 } as const;
  const sorted = [...(sprints ?? [])].sort((a, b) => (rank[a.status as keyof typeof rank] ?? 9) - (rank[b.status as keyof typeof rank] ?? 9));

  const itemSx = { py: 0.5 };

  // V14 Epic 段:顶层 type='goal' 的 item;rollup = 自身+子孙 feasSignal 取最差(红>黄>绿,DOMAIN 聚合规则)
  const epics = items.filter((i) => i.type === "goal" && !i.parentItemId);
  const childrenOf = new Map<string, NavItem[]>();
  for (const i of items) {
    if (!i.parentItemId) continue;
    const list = childrenOf.get(i.parentItemId) ?? [];
    list.push(i);
    childrenOf.set(i.parentItemId, list);
  }
  const sigRank: Record<Signal, number> = { RED: 0, YELLOW: 1, GREEN: 2 };
  const worstSignal = (id: string, seen = new Set<string>()): Signal | null => {
    if (seen.has(id)) return null; // 树环兕底
    seen.add(id);
    const self = items.find((i) => i.itemId === id);
    const signals: Signal[] = [];
    if (self?.feasSignal) signals.push(self.feasSignal);
    for (const c of childrenOf.get(id) ?? []) {
      const s = worstSignal(c.itemId, seen);
      if (s) signals.push(s);
    }
    return signals.length ? signals.reduce((a, b) => (sigRank[a] <= sigRank[b] ? a : b)) : null;
  };
  const dotColor: Record<Signal, string> = { GREEN: "success.main", YELLOW: "warning.main", RED: "error.main" };

  async function exportXlsx(projectKey: string) {
    const token = localStorage.getItem("yz-token");
    const res = await fetch(`${API_BASE}/api/projects/${projectKey}/export.xlsx`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) throw new Error("导出失败");
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${projectKey}-export.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <Box component="nav" sx={{ width: 210, flexShrink: 0, bgcolor: "grey.50" }}>
        <Box sx={{ px: 1.5, pt: 1.5, pb: 0.5, display: "flex", alignItems: "center", gap: 0.25 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 700, flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis" }}>
            {String(key).toUpperCase()}
          </Typography>
          <IconButton size="small" aria-label={t.wf.button} onClick={() => setWfOpen(true)}>
            <TuneIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" aria-label={t.membersPanel.button} onClick={() => setMembersOpen(true)}>
            <GroupIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" aria-label={t.gh.button} onClick={() => setGhOpen(true)}>
            <GitHubIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" aria-label="导出" onClick={() => exportXlsx(String(key))}>
            <DownloadIcon fontSize="small" />
          </IconButton>
        </Box>
        <List dense disablePadding sx={{ py: 2 }}>
          <ListItemButton component={Link} href={`/p/${key}/overview`} selected={pathname === `/p/${key}/overview`} sx={itemSx}>
            <ListItemText primary={t.projectNav.overview} />
          </ListItemButton>
          <ListItemButton component={Link} href={`/p/${key}`} selected={pathname === `/p/${key}`} sx={itemSx}>
            <ListItemText primary={t.projectNav.board} />
          </ListItemButton>
          <ListItemButton component={Link} href={`/p/${key}/gantt`} selected={pathname === `/p/${key}/gantt`} sx={itemSx}>
            <ListItemText primary={t.projectNav.gantt} />
          </ListItemButton>
          <ListItemButton component={Link} href={`/p/${key}/table`} selected={pathname === `/p/${key}/table`} sx={itemSx}>
            <ListItemText primary={t.projectNav.table} />
          </ListItemButton>
          <Divider sx={{ my: 1 }} />
          <ListSubheader disableSticky sx={{ bgcolor: "transparent", display: "flex", alignItems: "center" }}>
            <span style={{ flex: 1 }}>{t.projectNav.sprints}</span>
            <IconButton
              size="small"
              aria-label="新建 Sprint"
              onClick={() => setCreateOpen(true)}
            >
              <AddIcon fontSize="small" />
            </IconButton>
          </ListSubheader>
          <ListItemButton component={Link} href={`/p/${key}/backlog`} selected={pathname === `/p/${key}/backlog`} sx={itemSx}>
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
              selected={pathname === `/p/${key}/sprint/${s.sprintId}`}
              sx={itemSx}
            >
              <ListItemText
                primary={s.name}
                secondary={t.projectNav.sprintStatus[s.status as keyof typeof t.projectNav.sprintStatus] ?? s.status}
              />
            </ListItemButton>
          ))}
          <Divider sx={{ my: 1 }} />
          <ListSubheader disableSticky sx={{ bgcolor: "transparent" }}>{t.projectNav.epics}</ListSubheader>
          {epics.length === 0 && (
            <ListItemButton disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={t.projectNav.noEpics}
                primaryTypographyProps={{ variant: "caption", color: "text.secondary" }}
              />
            </ListItemButton>
          )}
          {epics.map((e) => {
            const sig = worstSignal(e.itemId);
            return (
              <ListItemButton
                key={e.itemId}
                component={Link}
                href={`/p/${key}/i/${e.itemId}`}
                sx={itemSx}
              >
                <Box component="span" sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
                  <Box
                    component="span"
                    aria-label={sig ? t.signal[sig] : undefined}
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: "50%",
                      flexShrink: 0,
                      bgcolor: sig ? dotColor[sig] : "transparent",
                      border: sig ? "none" : "1px solid",
                      borderColor: "grey.300",
                    }}
                  />
                  <Typography variant="body2" noWrap>{e.title}</Typography>
                </Box>
              </ListItemButton>
            );
          })}
          <Divider sx={{ my: 1 }} />
          <ListSubheader disableSticky sx={{ bgcolor: "transparent", display: "flex", alignItems: "center" }}>
            <span style={{ flex: 1 }}>{t.projectNav.milestones}</span>
            <IconButton size="small" aria-label={t.projectNav.manage} onClick={() => setMsOpen(true)}>
              <TuneIcon fontSize="small" />
            </IconButton>
          </ListSubheader>
          {milestones.length === 0 && (
            <ListItemButton disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={t.projectNav.noMilestones}
                primaryTypographyProps={{ variant: "caption", color: "text.secondary" }}
              />
            </ListItemButton>
          )}
          {milestones.map((m) => (
            <ListItemButton key={m.milestoneId} disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={m.name}
                secondary={[
                  t.milestone.status[m.status as keyof typeof t.milestone.status],
                  m.targetDate,
                ].filter(Boolean).join(" · ")}
              />
            </ListItemButton>
          ))}
          <Divider sx={{ my: 1 }} />
          <ListSubheader disableSticky sx={{ bgcolor: "transparent", display: "flex", alignItems: "center" }}>
            <span style={{ flex: 1 }}>{t.projectNav.releases}</span>
            <IconButton size="small" aria-label={t.projectNav.manageRelease} onClick={() => setRelOpen(true)}>
              <TuneIcon fontSize="small" />
            </IconButton>
          </ListSubheader>
          {releases.length === 0 && (
            <ListItemButton disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={t.projectNav.noReleases}
                primaryTypographyProps={{ variant: "caption", color: "text.secondary" }}
              />
            </ListItemButton>
          )}
          {releases.map((r) => (
            <ListItemButton key={r.releaseId} disabled sx={{ py: 0.25 }}>
              <ListItemText
                primary={`${r.name} (${r.memberCount})`}
                secondary={[
                  t.release.status[r.status as keyof typeof t.release.status],
                  r.targetDate ? `${t.release.targetDate} ${r.targetDate}` : null,
                  r.releasedDate ? `${t.release.releasedDate} ${r.releasedDate}` : null,
                ].filter(Boolean).join(" · ")}
              />
            </ListItemButton>
          ))}
        </List>
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 0 }}>
        {children}
      </Box>
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{t.projectNav.create}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label={t.projectNav.createName} size="small" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
            <TextField label="开始日期" type="date" size="small" value={startDate} onChange={(e) => setStartDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField label="截止日期" type="date" size="small" value={endDate} onChange={(e) => setEndDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            {error && <Typography color="error" variant="body2">{error}</Typography>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>{t.projectNav.cancel}</Button>
          <Button variant="contained" onClick={createSprint} disabled={!name.trim()}>
            {t.projectNav.createOk}
          </Button>
        </DialogActions>
      </Dialog>
      <MilestoneManager
        open={msOpen}
        onClose={() => setMsOpen(false)}
        projectKey={String(key)}
        milestones={milestones}
        onChanged={loadSide}
      />
      <ReleaseManager
        open={relOpen}
        onClose={() => setRelOpen(false)}
        projectKey={String(key)}
        releases={releases}
        onChanged={loadSide}
      />
      <WorkflowEditor
        projectKey={String(key)}
        open={wfOpen}
        onClose={() => setWfOpen(false)}
        onChanged={() => window.dispatchEvent(new Event("yz:project-changed"))}
      />
      <ProjectMembersPanel
        projectKey={String(key)}
        open={membersOpen}
        onClose={() => setMembersOpen(false)}
        onChanged={() => window.dispatchEvent(new Event("yz:project-changed"))}
      />
      <GithubSettingsPanel
        projectKey={String(key)}
        open={ghOpen}
        onClose={() => setGhOpen(false)}
      />
    </Box>
  );
}
