"use client";

import {
  Box,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Checkbox,
  Chip,
  Container,
  FormControlLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import type { Signal } from "@/components/Verdict";

type ProjectDto = {
  projectId: string;
  key: string;
  name: string;
  archived: boolean;
  feasSignal: Signal | null;
  statuses: { statusId: string; name: string; isFinal: boolean; position: number }[];
};

type Shortfall = {
  attribute: string;
  deltaSum: number;
  missingCount: number;
  unratedCount: number;
  items: { projectKey: string; itemId: string; number: string; title: string }[];
};

type StandupItem = { itemId: string; number: string; projectKey: string; title: string; statusName: string };
type Standup = { doneYesterday: StandupItem[]; today: StandupItem[]; blocked: StandupItem[] };
type NotifDto = { notificationId: string; itemId: string; number: string; projectKey: string; title: string };
type LiveSprint = { sprintId: string; name: string; endDate: string | null };
type LiveMilestone = { milestoneId: string; name: string; targetDate: string | null };
type LiveGroup = { projectKey: string; sprints: LiveSprint[]; milestones: LiveMilestone[] };
type DashboardDto = { standup: Standup; live: LiveGroup[] };

export default function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [shortfallList, setShortfallList] = useState<Shortfall[]>([]);
  const [favKeys, setFavKeys] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [sortBy, setSortBy] = useState("key");
  const [showArchived, setShowArchived] = useState(false);
  const [favHint, setFavHint] = useState(false);
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [standup, setStandup] = useState<Standup | null>(null);
  const [live, setLive] = useState<LiveGroup[]>([]);
  const [recent, setRecent] = useState<NotifDto[]>([]);

  async function load() {
    setProjects(await api<ProjectDto[]>("/api/projects"));
  }

  // 全局驾驶舱:后端一次聚合(我的三桶 + 各项目活跃 sprint/进行中 milestone),通知另取
  async function loadDashboard() {
    const [d, ns] = await Promise.all([api<DashboardDto>("/api/dashboard"), api<NotifDto[]>("/api/notifications")]);
    setStandup(d.standup);
    setLive(d.live);
    setRecent(ns.slice(0, 5));
  }

  useEffect(() => {
    load().catch(() => undefined);
    loadDashboard().catch(() => undefined);
  }, []);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await api("/api/projects", {
        method: "POST",
        body: JSON.stringify({ key: key.trim(), name: name.trim() || key.trim() }),
      });
      setKey("");
      setName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  async function toggleFav(projectKey: string) {
    const method = favKeys.includes(projectKey) ? "DELETE" : "PUT";
    try {
      await api(`/api/projects/${projectKey}/favorite`, { method });
      setFavKeys((prev) => (method === "PUT" ? [...prev, projectKey] : prev.filter((k) => k !== projectKey)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "收藏失败");
    }
  }

  const visibleProjects = useMemo(() => {
    let list = [...projects];
    if (search) list = list.filter((p) => p.key.toLowerCase().includes(search.toLowerCase()) || p.name.toLowerCase().includes(search.toLowerCase()));
    if (sortBy === "name") list.sort((a, b) => a.name.localeCompare(b.name));
    else if (sortBy === "signal") { const rank = (s: string | null) => (s === "RED" ? 0 : s === "YELLOW" ? 1 : s === "GREEN" ? 2 : 3); list.sort((a, b) => rank(a.feasSignal) - rank(b.feasSignal)); }
    else list.sort((a, b) => a.key.localeCompare(b.key));
    if (!showArchived) list = list.filter((p) => !p.archived);
    return list;
  }, [projects, search, sortBy, showArchived]);
  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Typography variant="h4" gutterBottom>
        {t.dashboard.title}
      </Typography>
      {standup && (
        <Stack direction="row" spacing={2} sx={{ mb: 4 }} useFlexGap flexWrap="wrap">
          {([
            { title: t.dashboard.today, items: standup.today, muted: false },
            { title: t.dashboard.blocked, items: standup.blocked, muted: false },
            { title: t.dashboard.doneYesterday, items: standup.doneYesterday, muted: true },
          ] as const).map((col) => (
            <Card key={col.title} variant="outlined" sx={{ flex: "1 1 240px", minWidth: 240 }}>
              <CardContent>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  {col.title}({col.items.length})
                </Typography>
                {col.items.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">{t.dashboard.none}</Typography>
                ) : (
                  col.items.map((it) => (
                    <Typography
                      key={it.itemId}
                      variant="body2"
                      component={Link}
                      href={`/p/${it.projectKey}/i/${it.itemId}`}
                      sx={{
                        display: "block",
                        mb: 0.5,
                        textDecoration: "none",
                        color: col.muted ? "text.secondary" : "text.primary",
                      }}
                    >
                      <Box component="span" sx={{ color: "primary.main", mr: 1 }}>{it.number}</Box>
                      {it.title}
                    </Typography>
                  ))
                )}
              </CardContent>
            </Card>
          ))}
        </Stack>
      )}
      {live.some((g) => g.sprints.length > 0 || g.milestones.length > 0) && (
        <Box sx={{ mb: 4 }}>
          <Typography variant="h6" gutterBottom>{t.dashboard.inProgress}</Typography>
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
            {live
              .flatMap((g) => [
                ...g.sprints.map((s) => ({ id: `s-${g.projectKey}-${s.sprintId}`, key: g.projectKey, label: t.dashboard.sprint, name: s.name, date: s.endDate })),
                ...g.milestones.map((m) => ({ id: `m-${g.projectKey}-${m.milestoneId}`, key: g.projectKey, label: t.dashboard.milestone, name: m.name, date: m.targetDate })),
              ])
              .map((x) => (
                <Card key={x.id} variant="outlined" sx={{ py: 1, px: 1.5 }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip label={x.key} size="small" />
                    <Chip label={x.label} size="small" variant="outlined" />
                    <Typography variant="body2">{x.name}</Typography>
                    {x.date && <Typography variant="caption" color="text.secondary">{x.date}</Typography>}
                  </Stack>
                </Card>
              ))}
          </Stack>
        </Box>
      )}
      {recent.length > 0 && (
        <Box sx={{ mb: 4 }}>
          <Stack direction="row" spacing={2} alignItems="baseline" sx={{ mb: 0.5 }}>
            <Typography variant="h6">{t.dashboard.recent}</Typography>
            <Typography variant="body2" component={Link} href="/notifications">{t.dashboard.viewAll}</Typography>
          </Stack>
          {recent.map((n) => (
            <Typography
              key={n.notificationId}
              variant="body2"
              component={Link}
              href={`/p/${n.projectKey}/i/${n.itemId}`}
              sx={{ display: "block", textDecoration: "none", color: "text.primary" }}
            >
              <Box component="span" sx={{ color: "primary.main", mr: 1 }}>{n.number}</Box>
              {n.title}
            </Typography>
          ))}
        </Box>
      )}
      <Typography variant="h5" gutterBottom>
        {t.projects.title}
      </Typography>
      <Stack component="form" direction="row" spacing={1} onSubmit={create} sx={{ mb: 3 }}>
        <TextField size="small" label={t.projects.createKey} value={key} onChange={(e) => setKey(e.target.value.toUpperCase())} required />
        <TextField size="small" label={t.projects.createName} value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" variant="contained">
          创建项目
        </Button>
      </Stack>
      {favHint && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2, p: 1, bgcolor: "action.hover", borderRadius: 1 }}>
          <Typography variant="body2">点项目卡右上角 ☆ 收藏常用项目,之后从导航 ⭐ 收藏直达。</Typography>
          <Button
            size="small"
            onClick={() => {
              localStorage.setItem("yz-fav-hint-dismissed", "1");
              setFavHint(false);
            }}
          >
            知道了
          </Button>
        </Stack>
      )}
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}
      {projects.length === 0 && <Typography color="text.secondary">{t.projects.empty}</Typography>}
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(260px,1fr))", gap: 2, mb: 4 }}>
        {visibleProjects.map((p) => (
          <Card key={p.key} sx={{ position: "relative" }}>
            <Button
              size="small"
              sx={{ position: "absolute", top: 2, right: 2, zIndex: 1, minWidth: 0 }}
              onClick={() => toggleFav(p.key)}
              aria-label="favorite"
            >
              {favKeys.includes(p.key) ? "★" : "☆"}
            </Button>
            <CardActionArea LinkComponent={Link} href={`/p/${p.key}`}>
              <CardContent>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip label={p.key} size="small" />
                  <Typography variant="h6">{p.name}</Typography>
                  {p.feasSignal && (
                    <Chip
                      label={t.signal[p.feasSignal]}
                      size="small"
                      variant="outlined"
                      color={p.feasSignal === "RED" ? "error" : p.feasSignal === "YELLOW" ? "warning" : "success"}
                    />
                  )}
                </Stack>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>

      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        <TextField size="small" placeholder="搜索项目…" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ width: 220 }} />
        <Select size="small" value={sortBy} onChange={(e) => setSortBy(String(e.target.value))} sx={{ width: 140 }}>
          <MenuItem value="key">按 KEY</MenuItem>
          <MenuItem value="name">按名称</MenuItem>
          <MenuItem value="signal">按可行性</MenuItem>
        </Select>
        <FormControlLabel
          control={<Checkbox size="small" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />}
          label={<Typography variant="caption">{t.projects.showArchived}</Typography>}
        />
      </Stack>
      <Typography variant="h5" gutterBottom>
        {t.shortfall.title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t.shortfall.hint}
      </Typography>
      {shortfallList.length === 0 ? (
        <Typography color="text.secondary">{t.shortfall.empty}</Typography>
      ) : (
        <Stack spacing={1}>
          {shortfallList.map((s) => (
            <Card key={s.attribute} variant="outlined">
              <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                  <Typography variant="subtitle1">{s.attribute}</Typography>
                  {s.missingCount > 0 && <Chip size="small" color="error" variant="outlined" label={t.shortfall.missing(s.missingCount)} />}
                  {s.deltaSum > 0 && <Chip size="small" color="warning" variant="outlined" label={t.shortfall.delta(s.deltaSum)} />}
                  {s.unratedCount > 0 && <Chip size="small" variant="outlined" label={t.shortfall.unrated(s.unratedCount)} />}
                </Stack>
                <Stack direction="row" spacing={1} flexWrap="wrap" sx={{ mt: 0.5 }}>
                  {s.items.map((it, i) => (
                    <Link
                      key={i}
                      href={`/p/${it.projectKey}/i/${it.itemId}`}
                      style={{ fontSize: 13, color: "primary.main", textDecoration: "none" }}
                    >
                      {it.number}
                    </Link>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      )}
    </Container>
  );
}
