"use client";

import {
  Box,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Container,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";
import type { Signal } from "@/components/Verdict";

type ProjectDto = {
  projectId: string;
  key: string;
  name: string;
  archived: boolean;
  statuses: { statusId: string; name: string; isFinal: boolean; position: number }[];
};

type Feasibility = {
  projectKey: string;
  signal: Signal;
  missingCount: number;
  totalDelta: number;
  items: {
    itemId: string;
    number: string;
    title: string;
    signal: Signal;
    verdicts: { kind: string; attribute: string; delta?: number | null }[];
  }[];
};

type Shortfall = {
  attribute: string;
  deltaSum: number;
  missingCount: number;
  unratedCount: number;
  items: { projectKey: string; itemId: string; number: string; title: string }[];
};

export default function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [feas, setFeas] = useState<Record<string, Feasibility>>({});
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  async function load() {
    const list = await api<ProjectDto[]>("/api/projects");
    setProjects(list);
    const map: Record<string, Feasibility> = {};
    await Promise.all(
      list.map(async (p) => {
        try {
          map[p.key] = await api<Feasibility>(`/api/projects/${p.key}/feasibility`);
        } catch {
          /* 单项失败不拦列表 */
        }
      }),
    );
    setFeas(map);
  }

  useEffect(() => {
    load().catch(() => undefined);
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

  // 短板聚合(客户端,瘦客户端原则):跨项目按属性汇缺口,回答"我该练什么"
  const shortfalls = Object.values(feas)
    .flatMap((f) => f.items.map((it) => ({ f, it })))
    .flatMap(({ f, it }) =>
      it.verdicts
        .filter((v) => v.kind === "gap" || v.kind === "unrated" || v.kind === "missing")
        .map((v) => ({ projectKey: f.projectKey, it, v })),
    )
    .reduce<Map<string, Shortfall>>((acc, { projectKey, it, v }) => {
      const cur =
        acc.get(v.attribute) ??
        ({ attribute: v.attribute, deltaSum: 0, missingCount: 0, unratedCount: 0, items: [] } as Shortfall);
      if (v.kind === "gap") cur.deltaSum += v.delta ?? 0;
      if (v.kind === "missing") cur.missingCount += 1;
      if (v.kind === "unrated") cur.unratedCount += 1;
      cur.items.push({ projectKey, itemId: it.itemId, number: it.number, title: it.title });
      acc.set(v.attribute, cur);
      return acc;
    }, new Map());
  const shortfallList = [...shortfalls.values()].sort(
    (a, b) => b.missingCount - a.missingCount || b.deltaSum - a.deltaSum,
  );

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h4" gutterBottom>
        {t.projects.title}
      </Typography>
      <Stack component="form" direction="row" spacing={1} onSubmit={create} sx={{ mb: 3 }}>
        <TextField size="small" label={t.projects.createKey} value={key} onChange={(e) => setKey(e.target.value.toUpperCase())} required />
        <TextField size="small" label={t.projects.createName} value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" variant="contained">
          创建项目
        </Button>
      </Stack>
      {error && <Typography color="error">{error}</Typography>}
      {projects.length === 0 && <Typography color="text.secondary">{t.projects.empty}</Typography>}
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(260px,1fr))", gap: 2, mb: 4 }}>
        {projects.map((p) => (
          <Card key={p.key}>
            <CardActionArea LinkComponent={Link} href={`/p/${p.key}`}>
              <CardContent>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip label={p.key} size="small" />
                  <Typography variant="h6">{p.name}</Typography>
                  {feas[p.key] && (
                    <Chip
                      label={t.signal[feas[p.key]!.signal]}
                      size="small"
                      variant="outlined"
                      color={feas[p.key].signal === "RED" ? "error" : feas[p.key].signal === "YELLOW" ? "warning" : "success"}
                    />
                  )}
                </Stack>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>

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
