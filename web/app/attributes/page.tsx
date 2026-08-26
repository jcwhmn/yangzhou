"use client";

import {
  Button,
  Chip,
  Container,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Attribute = { attributeId: string; name: string; kind: string; leveled: boolean };

/** 词表管理(kind × leveled,workspace 级;leveled 切换不删既有等级数据——休眠待唤醒,ADR-0003)。 */
export default function AttributesPage() {
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [name, setName] = useState("");
  const [kind, setKind] = useState("skill");
  const [leveled, setLeveled] = useState(true);
  const [error, setError] = useState("");

  async function load() {
    setAttributes(await api<Attribute[]>("/api/attributes"));
  }

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, []);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await api("/api/attributes", {
        method: "POST",
        body: JSON.stringify({ name: name.trim(), kind, leveled: kind === "skill" && leveled }),
      });
      setName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.attrs.createFailed);
    }
  }

  async function patch(a: Attribute, changes: Partial<Pick<Attribute, "kind" | "leveled">>) {
    setError("");
    try {
      await api(`/api/attributes/${a.attributeId}`, { method: "PATCH", body: JSON.stringify(changes) });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.attrs.updateFailed);
    }
  }

  async function remove(a: Attribute) {
    setError("");
    try {
      await api(`/api/attributes/${a.attributeId}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.attrs.deleteFailed);
    }
  }

  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        {t.attrs.title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t.attrs.hint}
      </Typography>
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}
      <Stack component="form" direction="row" spacing={1} onSubmit={create} sx={{ mb: 3 }} alignItems="center">
        <TextField size="small" label={t.attrs.name} value={name} onChange={(e) => setName(e.target.value)} required />
        <Button
          size="small"
          variant={kind === "skill" ? "contained" : "outlined"}
          onClick={() => setKind(kind === "skill" ? "label" : "skill")}
          type="button"
        >
          {kind === "skill" ? t.attrs.kindSkill : t.attrs.kindLabel}
        </Button>
        {kind === "skill" && (
          <FormControlLabel control={<Switch checked={leveled} onChange={(e) => setLeveled(e.target.checked)} />} label={t.attrs.leveled} />
        )}
        <Button type="submit" variant="contained">
          {t.attrs.add}
        </Button>
      </Stack>
      <Stack spacing={1}>
        {attributes.map((a) => (
          <Stack key={a.attributeId} direction="row" spacing={1} alignItems="center" justifyContent="space-between">
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography sx={{ minWidth: 110 }}>{a.name}</Typography>
              <Chip size="small" label={a.kind} variant="outlined" />
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center">
              <Button size="small" onClick={() => patch(a, { kind: a.kind === "skill" ? "label" : "skill" })}>
                {t.attrs.toKind(a.kind === "skill" ? "label" : "skill")}
              </Button>
              <Button size="small" disabled={a.kind !== "skill"} onClick={() => patch(a, { leveled: !a.leveled })}>
                {a.leveled ? t.attrs.unleveled : t.attrs.leveled}
              </Button>
              <Button size="small" color="error" onClick={() => remove(a)}>
                {t.attrs.delete}
              </Button>
            </Stack>
          </Stack>
        ))}
      </Stack>
    </Container>
  );
}
