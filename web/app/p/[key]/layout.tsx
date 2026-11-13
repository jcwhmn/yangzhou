"use client";

import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  List,
  ListItemButton,
  ListItemText,
  ListSubheader,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import Link from "next/link";
import { usePathname, useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, API_BASE } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  listGroupTree,
  createSprint as createSprintApi,
  syncCurrentParent,
  activeChainIds,
  type GroupTree,
  type GroupNode,
} from "@/lib/sprints";
import type { Milestone } from "@/components/MilestonePage";
import { ReleaseManager, type Release } from "@/components/ReleaseManager";
import {
  Category as CategoryIcon,
  Download as DownloadIcon,
  GitHub as GitHubIcon,
  Group as GroupIcon,
  Star as StarIcon,
  StarBorder as StarBorderIcon,
  Settings as SettingsIcon,
  Tune as TuneIcon,
} from "@mui/icons-material";
import type { Signal } from "@/components/Verdict";
import { WorkflowEditor } from "@/components/WorkflowEditor";
import { ProjectMembersPanel } from "@/components/ProjectMembersPanel";
import { GithubSettingsPanel } from "@/components/GithubSettingsPanel";
import { ProjectSettingsPanel } from "@/components/ProjectSettingsPanel";
import { GroupTypesPanel } from "@/components/GroupTypesPanel";
import { DateInput } from "@/components/DateInput";

type Sprint = { sprintId: string; name: string; status: string };
type GroupTypeLite = { name: string; level: number | null };
type NavItem = {
  itemId: string;
  number: string;
  title: string;
  type: string;
  parentItemId: string | null;
  feasSignal: Signal | null;
};

/**
 * V12-S2 项目侧边栏壳(spec 0010,裁决 3 最小 IA):
 * 看板(全部 items)+ Sprint 段(Backlog 派生桶入口 + 各 sprint,active→planned→completed)。
 * 现有甘特/表格/工时链接留在各页头部不动;Sprint 落地页在 S3。
 */
export default function ProjectLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { key } = useParams<{ key: string }>();
  const pathname = usePathname();
  const [sprints, setSprints] = useState<Sprint[] | null>(null);
  // YPJ-150 案B:level-1 类型完全用户配置——段头显示类型名;无类型 → 无段(null=加载中不渲染)
  const [l1, setL1] = useState<{ name: string } | null>(null);
  const [items, setItems] = useState<NavItem[]>([]);
  const [milestones, setMilestones] = useState<Milestone[]>([]);
  const [releases, setReleases] = useState<Release[]>([]);
  const [relOpen, setRelOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  // spec 0112:两级树——null = 平面模式(无 level-2 类型,现状);expanded = 用户手动开合的父层组集合
  const [tree, setTree] = useState<GroupTree | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [currentParentId, setCurrentParentId] = useState<string | null>(null);
  const [parentPick, setParentPick] = useState<string>("");
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [error, setError] = useState("");
  const [wfOpen, setWfOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  const [ghOpen, setGhOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [gtypeOpen, setGtypeOpen] = useState(false);
  // YPJ-151:release/milestone 启停(null=加载中按 true 渲染防闪;失败 fail-open)
  const [flags, setFlags] = useState<{
    release: boolean;
    milestone: boolean;
  } | null>(null);
  // YPJ-168(spec 0119):项目名(null=未加载/失败,不渲染名行防闪)
  const [projName, setProjName] = useState<string | null>(null);
  const [fav, setFav] = useState<boolean | null>(null);

  const loadSprints = () => {
    // CI 冷 JVM 偶发连接重置(status -1):失败隔 1s 重试一次
    const apply = (t: GroupTree) => {
      if (t.parentCandidates.length === 0) {
        // 平面模式(无 level-2 类型):全部叶层组现状渲染(spec 0114)
        setTree(null);
        setSprints(t.leafSprints);
        return;
      }
      setTree(t);
      setSprints(t.orphans);
      // 当前 epic:active 叶层组的父优先 → localStorage 记忆;兜底第一个 level-2 组(spec 0112→0114)
      setCurrentParentId(
        syncCurrentParent(key, t.leafSprints) ??
          t.parentCandidates[0]?.sprintId ??
          null,
      );
      // 展开态:active 叶层父链默认展开(仅加入不移除,保留用户手动开合)
      const chain = activeChainIds(t);
      if (chain.length > 0) setExpanded((prev) => new Set([...prev, ...chain]));
    };
    listGroupTree(key)
      .catch(
        () =>
          new Promise<GroupTree>((res) =>
            setTimeout(() => res(listGroupTree(key)), 1000),
          ),
      )
      .then(apply)
      .catch(() => {
        setTree(null);
        setSprints([]);
      });
    api<GroupTypeLite[]>(`/api/projects/${key}/group-types`)
      .then((ts) => setL1(ts.find((tp) => tp.level === 1) ?? null))
      .catch(() => setL1(null));
  };

  const loadFlags = () => {
    api<{
      name: string;
      releaseEnabled: boolean;
      milestoneEnabled: boolean;
    }>(`/api/projects/${key}`)
      .then((p) => {
        setProjName(p.name ?? null);
        setFlags({ release: p.releaseEnabled, milestone: p.milestoneEnabled });
      })
      .catch(() => {
        setProjName(null);
        setFlags({ release: true, milestone: true });
      });
  };

  const loadMilestones = () => {
    api<Milestone[]>(`/api/projects/${key}/milestones`)
      .then(setMilestones)
      .catch(() => setMilestones([]));
  };

  const loadSide = () => {
    loadFlags();
    api<NavItem[]>(`/api/projects/${key}/items`)
      .then(setItems)
      .catch(() => setItems([]));
    loadMilestones();
    api<Release[]>(`/api/projects/${key}/releases`)
      .then(setReleases)
      .catch(() => setReleases([]));
  };

  // YPJ-107:header 星标收藏状态(null=加载中;失败静默视为未收藏)
  const loadFav = () => {
    api<{ projectId: string; key: string }[]>("/api/favorites")
      .then((favs) =>
        setFav(
          favs.some((f) => f.key.toUpperCase() === String(key).toUpperCase()),
        ),
      )
      .catch(() => setFav(false));
  };

  async function toggleFav() {
    if (fav === null) return;
    setFav(!fav);
    try {
      await api(`/api/projects/${key}/favorite`, {
        method: fav ? "DELETE" : "PUT",
      });
    } catch {
      setFav(fav); // 失败回滚
    }
  }

  useEffect(() => {
    loadSprints();
    loadSide();
    loadFav();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  // YPJ-74:sprint 页改状态后广播 sprints-changed,sidebar 即时重拉(不再等下次挂载)
  useEffect(() => {
    const onSprintsChanged = () => loadSprints();
    window.addEventListener("sprints-changed", onSprintsChanged);
    return () =>
      window.removeEventListener("sprints-changed", onSprintsChanged);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  // YPJ-176:milestone 页建/改/删(含完成接棒级联)后广播 milestones-changed,sidebar 即时重拉(YPJ-74 同型)
  useEffect(() => {
    const onMilestonesChanged = () => loadMilestones();
    window.addEventListener("milestones-changed", onMilestonesChanged);
    return () =>
      window.removeEventListener("milestones-changed", onMilestonesChanged);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  async function createSprint() {
    try {
      await createSprintApi(key, {
        name,
        startDate: startDate || null,
        endDate: endDate || null,
        parentGroupId: parentPick || null, // spec 0112:默认当前 epic,可改
      });
      setCreateOpen(false);
      setName("");
      setStartDate("");
      setEndDate("");
      setError("");
      loadSprints();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    }
  }

  const rank = { active: 0, planned: 1, completed: 2 } as const;
  const sorted = [...(sprints ?? [])].sort(
    (a, b) =>
      (rank[a.status as keyof typeof rank] ?? 9) -
      (rank[b.status as keyof typeof rank] ?? 9),
  );

  const itemSx = { py: 0.5 };

  // spec 0112/0114:叶层组行(plUnits=缩进档;平面区无缩进,样式与导航逻辑同现状)
  const sprintRow = (s: Sprint, plUnits = 0) => (
    <ListItemButton
      key={s.sprintId}
      component={Link}
      href={`/p/${key}/sprint/${s.sprintId}`}
      selected={pathname === `/p/${key}/sprint/${s.sprintId}`}
      sx={{ ...itemSx, ...(plUnits > 0 ? { pl: plUnits } : {}) }}
    >
      <ListItemText
        primary={s.name}
        secondary={
          t.projectNav.sprintStatus[
            s.status as keyof typeof t.projectNav.sprintStatus
          ] ?? s.status
        }
      />
    </ListItemButton>
  );

  // spec 0114:递归树渲染——非叶层行点开合(+/− 前缀),叶层行走 sprintRow;缩进按深度
  const nodeRows = (ns: GroupNode[], depth: number) =>
    ns.map((n) => {
      if (!n.sprint.isParent) return sprintRow(n.sprint, (depth + 1) * 2);
      const open = expanded.has(n.sprint.sprintId);
      const toggle = () =>
        setExpanded((prev) => {
          const next = new Set(prev);
          if (next.has(n.sprint.sprintId)) next.delete(n.sprint.sprintId);
          else next.add(n.sprint.sprintId);
          return next;
        });
      return (
        <Box key={n.sprint.sprintId}>
          <ListItemButton
            onClick={n.children.length > 0 ? toggle : undefined}
            sx={{ ...itemSx, pl: (depth + 1) * 2 }}
          >
            <ListItemText
              primary={
                (n.children.length === 0 ? "" : open ? "− " : "+ ") +
                n.sprint.name
              }
              secondary={
                t.projectNav.sprintStatus[
                  n.sprint.status as keyof typeof t.projectNav.sprintStatus
                ] ?? n.sprint.status
              }
            />
          </ListItemButton>
          {open && nodeRows(n.children, depth + 1)}
        </Box>
      );
    });

  async function exportXlsx(projectKey: string) {
    const token = localStorage.getItem("yz-token");
    const res = await fetch(
      `${API_BASE}/api/projects/${projectKey}/export.xlsx`,
      {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      },
    );
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
      <Box
        component="nav"
        sx={{ width: 210, flexShrink: 0, bgcolor: "grey.50" }}
      >
        <Box sx={{ px: 1.5, pt: 1.5, pb: 0.5 }}>
          {projName && (
            <Typography
              variant="subtitle2"
              data-testid="sidebar-project-name"
              sx={{
                fontWeight: 700,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                mb: 0.5,
              }}
            >
              {projName}
            </Typography>
          )}
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.25 }}>
            <Typography
              variant="subtitle2"
              sx={{
                fontWeight: 700,
                flex: 1,
                minWidth: 0,
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {String(key).toUpperCase()}
            </Typography>
            <IconButton
              size="small"
              aria-label="favorite"
              disabled={fav === null}
              onClick={toggleFav}
            >
              {fav ? (
                <StarIcon fontSize="small" />
              ) : (
                <StarBorderIcon fontSize="small" />
              )}
            </IconButton>
            <IconButton
              size="small"
              aria-label={t.wf.button}
              onClick={() => setWfOpen(true)}
            >
              <TuneIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              aria-label={t.membersPanel.button}
              onClick={() => setMembersOpen(true)}
            >
              <GroupIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              aria-label={t.groupTypes.title}
              onClick={() => setGtypeOpen(true)}
            >
              <CategoryIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              aria-label={t.settings.title}
              onClick={() => setSettingsOpen(true)}
            >
              <SettingsIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              aria-label={t.gh.button}
              onClick={() => setGhOpen(true)}
            >
              <GitHubIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              aria-label="导出"
              onClick={() => exportXlsx(String(key))}
            >
              <DownloadIcon fontSize="small" />
            </IconButton>
          </Box>
        </Box>
        <List dense disablePadding sx={{ py: 2 }}>
          <ListItemButton
            component={Link}
            href={`/p/${key}/overview`}
            selected={pathname === `/p/${key}/overview`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.overview} />
          </ListItemButton>
          <ListItemButton
            component={Link}
            href={`/p/${key}/flow`}
            selected={pathname === `/p/${key}/flow`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.flow} />
          </ListItemButton>
          <ListItemButton
            component={Link}
            href={`/p/${key}/board`}
            selected={pathname === `/p/${key}/board`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.board} />
          </ListItemButton>
          <ListItemButton
            component={Link}
            href={`/p/${key}/gantt`}
            selected={pathname === `/p/${key}/gantt`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.gantt} />
          </ListItemButton>
          <ListItemButton
            component={Link}
            href={`/p/${key}/table`}
            selected={pathname === `/p/${key}/table`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.table} />
          </ListItemButton>
          <Divider sx={{ my: 1 }} />
          <ListItemButton
            component={Link}
            href={`/p/${key}/backlog`}
            selected={pathname === `/p/${key}/backlog`}
            sx={itemSx}
          >
            <ListItemText primary={t.projectNav.backlog} />
          </ListItemButton>
          {l1 && (
            <>
              <ListSubheader
                disableSticky
                sx={{
                  bgcolor: "transparent",
                  display: "flex",
                  alignItems: "center",
                }}
              >
                <span style={{ flex: 1 }}>{l1.name}</span>
                <IconButton
                  size="small"
                  aria-label={`新建 ${l1.name}`}
                  onClick={() => {
                    // spec 0112:默认「当前 epic」;不变量下兜底第一个父层组
                    setParentPick(
                      currentParentId ??
                        tree?.parentCandidates[0]?.sprintId ??
                        "",
                    );
                    setCreateOpen(true);
                  }}
                >
                  <AddIcon fontSize="small" />
                </IconButton>
              </ListSubheader>
              {(sprints ?? []).length === 0 && sprints !== null && (
                <ListItemButton disabled sx={{ py: 0.25 }}>
                  <ListItemText
                    primary={`暂无${l1.name}`}
                    primaryTypographyProps={{
                      variant: "caption",
                      color: "text.secondary",
                    }}
                  />
                </ListItemButton>
              )}
              {tree ? (
                <>
                  {nodeRows(tree.nodes, 0)}
                  {tree.orphans.map((s) => sprintRow(s))}
                </>
              ) : (
                sorted.map((s) => sprintRow(s))
              )}
            </>
          )}
          {flags?.milestone !== false && (
            <>
              <Divider sx={{ my: 1 }} />
              {/* 票 1 真值表:段头常驻可点进页;行只显当前(0..1 in_progress);空态提示;双徽标上色 */}
              <ListSubheader
                disableSticky
                component={Link}
                href={`/p/${key}/milestones`}
                sx={{
                  bgcolor: "transparent",
                  display: "flex",
                  alignItems: "center",
                  textDecoration: "none",
                  color: "inherit",
                }}
              >
                <span style={{ flex: 1 }}>{t.projectNav.milestones}</span>
              </ListSubheader>
              {milestones
                .filter((m) => m.status === "in_progress")
                .map((m) => (
                  <ListItemButton
                    key={m.milestoneId}
                    component={Link}
                    href={`/p/${key}/milestones`}
                    sx={{ py: 0.25 }}
                  >
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Typography variant="body2" noWrap>
                        {`▶ ${m.name}`}
                        {m.targetDate ? ` · ${m.targetDate}` : ""}
                      </Typography>
                    </Box>
                    {m.overdue && (
                      <Typography
                        variant="caption"
                        color="error"
                        data-testid="milestone-nav-badge"
                      >
                        ⚠ {t.milestone.overdue}
                      </Typography>
                    )}
                    {m.dueSoon && (
                      <Typography
                        variant="caption"
                        color="warning.main"
                        data-testid="milestone-nav-badge"
                      >
                        ⚠ {t.milestone.dueSoon}
                      </Typography>
                    )}
                  </ListItemButton>
                ))}
              {milestones.every((m) => m.status !== "in_progress") && (
                <ListItemButton
                  disabled
                  sx={{ py: 0.25 }}
                  data-testid="milestone-nav-empty"
                >
                  <ListItemText
                    primary={t.milestone.noCurrent}
                    primaryTypographyProps={{
                      variant: "caption",
                      color: "text.secondary",
                    }}
                  />
                </ListItemButton>
              )}
            </>
          )}
          {flags?.release !== false && (
            <>
              <Divider sx={{ my: 1 }} />
              <ListSubheader
                disableSticky
                sx={{
                  bgcolor: "transparent",
                  display: "flex",
                  alignItems: "center",
                }}
              >
                <span style={{ flex: 1 }}>{t.projectNav.releases}</span>
                <IconButton
                  size="small"
                  aria-label={t.projectNav.manageRelease}
                  onClick={() => setRelOpen(true)}
                >
                  <TuneIcon fontSize="small" />
                </IconButton>
              </ListSubheader>
              {releases.length === 0 && (
                <ListItemButton disabled sx={{ py: 0.25 }}>
                  <ListItemText
                    primary={t.projectNav.noReleases}
                    primaryTypographyProps={{
                      variant: "caption",
                      color: "text.secondary",
                    }}
                  />
                </ListItemButton>
              )}
              {releases.map((r) => (
                <ListItemButton key={r.releaseId} disabled sx={{ py: 0.25 }}>
                  <ListItemText
                    primary={`${r.name} (${r.memberCount})`}
                    secondary={[
                      t.release.status[
                        r.status as keyof typeof t.release.status
                      ],
                      r.targetDate
                        ? `${t.release.targetDate} ${r.targetDate}`
                        : null,
                      r.releasedDate
                        ? `${t.release.releasedDate} ${r.releasedDate}`
                        : null,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  />
                </ListItemButton>
              ))}
            </>
          )}
        </List>
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 0 }}>
        {children}
      </Box>
      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>{`新建${l1?.name ?? ""}`}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label={t.projectNav.createName}
              size="small"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
            />
            <DateInput
              label="开始日期"
              size="small"
              value={startDate}
              onChange={setStartDate}
            />
            <DateInput
              label="截止日期"
              size="small"
              value={endDate}
              onChange={setEndDate}
            />
            {(tree?.parentCandidates.length ?? 0) > 0 && (
              <TextField
                select
                size="small"
                label={`所属${tree?.parentTypeName ?? ""}`}
                value={parentPick}
                onChange={(e) => setParentPick(e.target.value)}
              >
                {/* spec 0112/0114 不变量:新建必挂父(level-2),无「无」选项 */}
                {tree!.parentCandidates.map((p) => (
                  <MenuItem key={p.sprintId} value={p.sprintId}>
                    {p.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
            {error && (
              <Typography color="error" variant="body2">
                {error}
              </Typography>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>
            {t.projectNav.cancel}
          </Button>
          <Button
            variant="contained"
            onClick={createSprint}
            disabled={!name.trim()}
          >
            {t.projectNav.createOk}
          </Button>
        </DialogActions>
      </Dialog>
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
      <ProjectSettingsPanel
        projectKey={String(key)}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onChanged={loadFlags}
      />
      <GroupTypesPanel
        projectKey={String(key)}
        open={gtypeOpen}
        onClose={() => {
          setGtypeOpen(false);
          loadSprints(); // YPJ-150:类型管理可能新建 level-1 → 重判 Sprint 段显隐
        }}
      />
    </Box>
  );
}
