"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, API_BASE } from "@/lib/api";
import { t } from "@/lib/texts";
import { WorkflowEditor } from "@/components/WorkflowEditor";
import { ProjectMembersPanel } from "@/components/ProjectMembersPanel";
import { GithubSettingsPanel } from "@/components/GithubSettingsPanel";
import type { Signal } from "@/components/Verdict";

type Status = { statusId: string; name: string; icon: string | null; isStart: boolean; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  assigneeColor: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
  feasSignal: Signal | null;
  dueDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  blocked: boolean;
};

type Filter = "all" | "unassigned" | "blocked" | string; // string = memberId

export default function BoardPage() {
  const { key } = useParams<{ key: string }>();
  const router = useRouter();
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [newTitle, setNewTitle] = useState("");
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>("all");
  const [wfOpen, setWfOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  const [ghOpen, setGhOpen] = useState(false);

  // V9-Q3:看板 30s 自动刷新(手刷按钮同函数)
  useEffect(() => {
    const t = setInterval(() => load(), 30000);
    return () => clearInterval(t);
  }, []);

  const load = useCallback(async () => {
    try {
      const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
      setStatuses(project.statuses);
      // V9-S1:item 列表自带 feasSignal 冗余,不再单 feasibility 调用
      setItems(await api<Item[]>(`/api/projects/${key}/items`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  async function addItem(e: React.FormEvent) {
    e.preventDefault();
    if (!newTitle.trim()) return;
    try {
      await api(`/api/projects/${key}/items`, { method: "POST", body: JSON.stringify({ title: newTitle.trim() }) });
      setNewTitle("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

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

  async function moveTo(itemId: string, status: Status) {
    const item = items.find((i) => i.itemId === itemId);
    if (!item || item.status === status.name) return;
    try {
      setItems((prev) => prev.map((i) => (i.itemId === itemId ? { ...i, status: status.name } : i)));
      await api(`/api/items/${itemId}`, { method: "PATCH", body: JSON.stringify({ statusItemId: status.statusId }) });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.moveFailed);
      await load();
    }
  }

  // S7:assignee 过滤(纯前端;"未指派" = assignee 为 null)
  const filtered =
    filter === "all" ? items
    : filter === "unassigned" ? items.filter((i) => !i.assignee)
    : filter === "blocked" ? items.filter((i) => i.blocked)
    : items.filter((i) => i.assignee === filter);
  const memberOptions = [...new Set(items.map((i) => i.assignee).filter((a): a is string => !!a))];

  const byStatusName = new Map<string, Item[]>();
  statuses.forEach((s) => byStatusName.set(s.name, []));
  filtered.forEach((it) => {
    const list = byStatusName.get(it.status) ?? [];
    list.push(it);
    byStatusName.set(it.status, list);
  });

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{String(key).toUpperCase()} · 看板</Typography>
        <Stack component="form" direction="row" spacing={1} onSubmit={addItem}>
          <TextField
            size="small"
            placeholder={t.board.addItem}
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
          />
          <Button type="submit" variant="outlined">
            {t.board.addItem}
          </Button>
        </Stack>
        <Box sx={{ ml: "auto" }}>
          <Button size="small" onClick={() => setWfOpen(true)}>
            {t.wf.button}
          </Button>
          <Button size="small" onClick={() => setMembersOpen(true)}>
            {t.membersPanel.button}
          </Button>
          <Button size="small" onClick={() => setGhOpen(true)}>
            {t.gh.button}
          </Button>
          <Button size="small" onClick={() => load()}>
            {t.board.refresh}
          </Button>
          <Button size="small" component={Link} href={`/p/${key}/gantt`}>
            甘特
          </Button>
          <Button size="small" component={Link} href={`/p/${key}/table`}>
            表格
          </Button>
          <Button size="small" component={Link} href={`/p/${key}/time`}>
            工时
          </Button>
          <Button size="small" onClick={() => exportXlsx(String(key))}>
            导出
          </Button>
        </Box>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* S7:assignee 过滤 chips */}
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        <Chip label={t.filter.all} size="small" color={filter === "all" ? "primary" : "default"} onClick={() => setFilter("all")} />
        <Chip
          label={t.filter.unassigned}
          size="small"
          color={filter === "unassigned" ? "primary" : "default"}
          onClick={() => setFilter("unassigned")}
        />
        <Chip
          label="⛔ 被阻塞"
          size="small"
          color={filter === "blocked" ? "primary" : "default"}
          onClick={() => setFilter("blocked")}
        />
        {memberOptions.map((name) => (
          <Chip
            key={name}
            label={`👤 ${name}`}
            size="small"
            color={filter === name ? "primary" : "default"}
            onClick={() => setFilter(name)}
          />
        ))}
      </Stack>

      {loading ? (
        <Stack direction="row" spacing={2}>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} variant="rounded" width={280} height={400} />
          ))}
        </Stack>
      ) : (
        <Stack direction="row" spacing={2} sx={{ overflowX: "auto", alignItems: "flex-start", pb: 2 }}>
          {statuses.map((s) => {
            const list = byStatusName.get(s.name) ?? [];
            return (
              <Box
                key={s.statusId}
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(s.statusId);
                }}
                onDragLeave={() => setDragOver((cur) => (cur === s.statusId ? null : cur))}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(null);
                  const itemId = e.dataTransfer.getData("text/plain");
                  if (itemId) moveTo(itemId, s);
                }}
                sx={{
                  minWidth: 280,
                  minHeight: 300,
                  p: 1,
                  borderRadius: 2,
                  bgcolor: dragOver === s.statusId ? "action.hover" : "background.default",
                  border: "1px dashed",
                  borderColor: dragOver === s.statusId ? "primary.main" : "divider",
                }}
              >
                <Typography variant="subtitle2" sx={{ px: 1, py: 0.5 }}>
                  {s.icon ? `${s.icon} ` : ""}
                  {s.name}({list.length})
                  {s.isFinal && " [终态]"}
                </Typography>
                <Stack spacing={1}>
                  {list.length === 0 && (
                    <Typography variant="caption" color="text.secondary" sx={{ px: 1 }}>
                      (空)
                    </Typography>
                  )}
                  {list.map((it) => (
                    <Card
                      key={it.itemId}
                      draggable
                      onDragStart={(e) => e.dataTransfer.setData("text/plain", it.itemId)}
                      onClick={() => router.push(`/p/${key}/i/${it.itemId}`)}
                      sx={{
                        cursor: "pointer",
                        borderLeft: "4px solid " + (it.assigneeColor ?? "#9e9e9e"),
                        transition: "box-shadow .15s, border-color .15s",
                        "&:hover": { boxShadow: 6, borderColor: "primary.main" },
                      }}
                    >
                      <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                        <Stack spacing={0.5}>
                          <Stack direction="row" spacing={1} alignItems="center">
                            <Chip label={it.number} size="small" variant="outlined" />
                            {it.feasSignal && it.feasSignal !== "GREEN" && (
                              <Chip
                                size="small"
                                label={t.signal[it.feasSignal]}
                                color={it.feasSignal === "RED" ? "error" : "warning"}
                              />
                            )}
                            {it.blocked && (
                              <Chip size="small" color="error" variant="outlined" label="⛔ 被阻塞" />
                            )}
                            {it.assignee && (
                              <Typography variant="caption" color="text.secondary">
                                👤 {it.assignee}
                              </Typography>
                            )}
                          </Stack>
                          <Link
                            href={`/p/${key}/i/${it.itemId}`}
                            style={{ textDecoration: "none", color: "inherit" }}
                          >
                            <Typography variant="body2" sx={{ wordBreak: "break-word" }}>
                              {it.title}
                            </Typography>
                          </Link>
                          {(it.dueDate || it.overdue || it.dueSoon) && (
                            <Typography
                              variant="caption"
                              sx={{
                                color: it.overdue ? "error.main" : it.dueSoon ? "warning.main" : "text.secondary",
                                fontWeight: it.overdue || it.dueSoon ? 700 : 400,
                              }}
                            >
                              {it.overdue ? "⚠ 超期 " : it.dueSoon ? "⏳ 将到期 " : "📅 "}
                              {it.dueDate}
                            </Typography>
                          )}
                          {it.requirements.length > 0 && (
                            <Typography variant="caption" color="text.secondary">
                              {it.requirements
                                .map((r) => (r.minLevel ? `${r.attribute}≥${r.minLevel}` : r.attribute))
                                .join(" · ")}
                            </Typography>
                          )}
                        </Stack>
                      </CardContent>
                    </Card>
                  ))}
                </Stack>
              </Box>
            );
          })}
        </Stack>
      )}

      <WorkflowEditor
        projectKey={String(key)}
        open={wfOpen}
        onClose={() => setWfOpen(false)}
        onChanged={load}
      />
      <ProjectMembersPanel
        projectKey={String(key)}
        open={membersOpen}
        onClose={() => setMembersOpen(false)}
        onChanged={load}
      />
      <GithubSettingsPanel
        projectKey={String(key)}
        open={ghOpen}
        onClose={() => setGhOpen(false)}
      />
    </Box>
  );
}
