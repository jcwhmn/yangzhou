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
import { t } from "@/lib/texts";
import type { Signal } from "@/components/Verdict";

type ProjectDto = {
  projectId: string;
  key: string;
  name: string;
  archived: boolean;
  statuses: { statusId: string; name: string; isFinal: boolean; position: number }[];
};

type FeasibilityDto = { signal: Signal };

export default function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [feas, setFeas] = useState<Record<string, Signal>>({});
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  async function load() {
    const list = await api<ProjectDto[]>("/api/projects");
    setProjects(list);
    const map: Record<string, Signal> = {};
    await Promise.all(
      list.map(async (p) => {
        try {
          const f = await api<FeasibilityDto>(`/api/projects/${p.key}/feasibility`);
          map[p.key] = f.signal;
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
      await api("/api/projects", { method: "POST", body: JSON.stringify({ key: key.trim(), name: name.trim() || key.trim() }) });
      setKey("");
      setName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Typography variant="h4" gutterBottom>
        {t.projects.title}
      </Typography>
      <Stack component="form" direction="row" spacing={1} onSubmit={create} sx={{ mb: 3 }}>
        <TextField size="small" label={t.projects.createKey} value={key} onChange={(e) => setKey(e.target.value.toUpperCase())} required />
        <TextField size="small" label={t.projects.createName} value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" variant="contained">
          {t.projects.create}
        </Button>
      </Stack>
      {error && <Typography color="error">{error}</Typography>}
      {projects.length === 0 && <Typography color="text.secondary">{t.projects.empty}</Typography>}
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(260px,1fr))", gap: 2 }}>
        {projects.map((p) => (
          <Card key={p.key}>
            <CardActionArea LinkComponent={Link} href={`/p/${p.key}`}>
              <CardContent>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip label={p.key} size="small" />
                  <Typography variant="h6">{p.name}</Typography>
                  {feas[p.key] && <Chip label={t.signal[feas[p.key]]} size="small" variant="outlined" />}
                </Stack>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>
    </Container>
  );
}
