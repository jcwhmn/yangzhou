"use client";

import {
  Box,
  Button,
  Chip,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { SignalChip, VerdictLine, type Signal, type Verdict } from "@/components/Verdict";

type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  description: string | null;
  type: string;
  status: string;
  parentItemId: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
};
type Attribute = { attributeId: string; name: string; kind: string; leveled: boolean };
type Feasibility = {
  itemId: string;
  number: string;
  title: string;
  signal: Signal;
  missingCount: number;
  totalDelta: number;
  verdicts: Verdict[];
};

type ReqRow = { attribute: string; minLevel: number | null };

export default function ItemDetailPage() {
  const { key, itemId } = useParams<{ key: string; itemId: string }>();
  const [item, setItem] = useState<Item | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [feasibility, setFeasibility] = useState<Feasibility | null>(null);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [reqs, setReqs] = useState<ReqRow[]>([]);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const it = await api<Item>(`/api/items/${itemId}`);
    setItem(it);
    setTitle(it.title);
    setDescription(it.description ?? "");
    setReqs(it.requirements.map((r) => ({ ...r })));
    const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
    setStatuses(project.statuses);
    setAttributes(await api<Attribute[]>("/api/attributes"));
    setFeasibility(await api<Feasibility>(`/api/items/${itemId}/feasibility`));
  }, [key, itemId]);

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, [load]);

  const flashSaved = () => {
    setSaved(true);
    setTimeout(() => setSaved(false), 1500);
  };

  async function saveBasics() {
    await api(`/api/items/${itemId}`, {
      method: "PATCH",
      body: JSON.stringify({ title, description: description || null }),
    });
    await load();
    flashSaved();
  }

  async function moveStatus(statusName: string) {
    const target = statuses.find((s) => s.name === statusName);
    if (!target) return;
    await api(`/api/items/${itemId}`, { method: "PATCH", body: JSON.stringify({ statusItemId: target.statusId }) });
    await load();
  }

  async function saveRequirements() {
    await api(`/api/items/${itemId}/requirements`, {
      method: "PUT",
      body: JSON.stringify({ requirements: reqs.filter((r) => r.attribute) }),
    });
    await load(); // 判定行与聚合信号即时刷新
    flashSaved();
  }

  if (!item) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color={error ? "error" : "text.secondary"}>{error || "加载中…"}</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 760, mx: "auto", p: 3 }}>
      <Link href={`/p/${key}`} style={{ textDecoration: "none" }}>
        <Typography variant="body2" color="primary" gutterBottom>
          {t.item.back}
        </Typography>
      </Link>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        <Chip label={item.number} />
        {feasibility && <SignalChip signal={feasibility.signal} />}
        <Select size="small" value={item.status} onChange={(e) => moveStatus(String(e.target.value))} sx={{ ml: "auto", minWidth: 160 }}>
          {statuses.map((s) => (
            <MenuItem key={s.statusId} value={s.name}>
              {s.name}
            </MenuItem>
          ))}
        </Select>
      </Stack>

      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}

      <Stack spacing={2} component="section">
        <TextField label={t.item.title} value={title} onChange={(e) => setTitle(e.target.value)} fullWidth />
        <TextField label={t.item.description} value={description} onChange={(e) => setDescription(e.target.value)} multiline minRows={2} fullWidth />
        <Stack direction="row" spacing={1} alignItems="center">
          <Button variant="contained" onClick={saveBasics}>
            {t.item.save}
          </Button>
          {saved && <Typography variant="body2" color="text.secondary">{t.item.saved}</Typography>}
        </Stack>
      </Stack>

      <Typography variant="h6" sx={{ mt: 3, mb: 1 }}>
        {t.item.requirements}
      </Typography>
      <Stack spacing={1}>
        {reqs.map((r, idx) => (
          <Stack key={idx} direction="row" spacing={1} alignItems="center">
            <Select
              size="small"
              value={r.attribute}
              onChange={(e) => setReqs((prev) => prev.map((x, i) => (i === idx ? { ...x, attribute: String(e.target.value) } : x)))}
              sx={{ minWidth: 200 }}
              displayEmpty
            >
              <MenuItem value="" disabled>
                {t.item.attribute}
              </MenuItem>
              {attributes.map((a) => (
                <MenuItem key={a.attributeId} value={a.name}>
                  {a.name}
                </MenuItem>
              ))}
            </Select>
            <Select
              size="small"
              value={r.minLevel ?? ""}
              onChange={(e) =>
                setReqs((prev) =>
                  prev.map((x, i) => (i === idx ? { ...x, minLevel: String(e.target.value) === "" ? null : Number(e.target.value) } : x)),
                )
              }
              sx={{ minWidth: 160 }}
              displayEmpty
            >
              <MenuItem value="">{t.item.none}</MenuItem>
              {[1, 2, 3, 4].map((l) => (
                <MenuItem key={l} value={l}>
                  ≥{l}
                </MenuItem>
              ))}
            </Select>
            <IconButton onClick={() => setReqs((prev) => prev.filter((_, i) => i !== idx))} aria-label="delete">
              <DeleteIcon />
            </IconButton>
          </Stack>
        ))}
        <Stack direction="row" spacing={1}>
          <Button startIcon={<AddIcon />} onClick={() => setReqs((prev) => [...prev, { attribute: "", minLevel: null }])}>
            {t.item.addRequirement}
          </Button>
          <Button variant="contained" onClick={saveRequirements}>
            {t.item.save}
          </Button>
        </Stack>
      </Stack>

      {feasibility && (
        <Box sx={{ mt: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t.item.verdicts}
          </Typography>
          {feasibility.verdicts.length === 0 && <Typography color="text.secondary">(无需求)</Typography>}
          {feasibility.verdicts.map((v, i) => (
            <VerdictLine key={i} v={v} />
          ))}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            缺门 {feasibility.missingCount} · 总差距 {feasibility.totalDelta} 级
          </Typography>
        </Box>
      )}
    </Box>
  );
}
