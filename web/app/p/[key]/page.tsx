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
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { AppNav } from "@/components/AppNav";
import { WorkflowEditor } from "@/components/WorkflowEditor";
import { ProjectMembersPanel } from "@/components/ProjectMembersPanel";
import type { Signal } from "@/components/Verdict";

type Status = { statusId: string; name: string; icon: string | null; isStart: boolean; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
};
type Feasibility = { signal: Signal };

type Filter = "all" | "unassigned" | string; // string = memberId

export default function BoardPage() {
  const { key } = useParams<{ key: string }>();
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [feas, setFeas] = useState<Record<string, Signal>>({});
  const [newTitle, setNewTitle] = useState("");
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>("all");
  const [wfOpen, setWfOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
      setStatuses(project.statuses);
      const list = await api<Item[]>(`/api/projects/${key}/items`);
      setItems(list);
      const map: Record<string, Signal> = {};
      await Promise.all(
        list.map(async (it) => {
          try {
            map[it.itemId] = (await api<Feasibility>(`/api/items/${it.itemId}/feasibility`)).signal;
          } catch {
            /* 单项失败不拦看板 */
          }
        }),
      );
      setFeas(map);
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
  const filtered = filter === "all" ? items : filter === "unassigned" ? items.filter((i) => !i.assignee) : items.filter((i) => i.assignee === filter);
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
      <AppNav />
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
                      sx={{ cursor: "grab" }}
                    >
                      <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                        <Stack spacing={0.5}>
                          <Stack direction="row" spacing={1} alignItems="center">
                            <Chip label={it.number} size="small" variant="outlined" />
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
    </Box>
  );
}
