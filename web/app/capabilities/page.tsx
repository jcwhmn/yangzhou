"use client";

import { Button, Container, MenuItem, Select, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Attribute = { attributeId: string; name: string; kind: string; leveled: boolean };
type Capability = { attribute: string; level: number | null };

/** 集中自评:每个词表属性一行(无/未评级/1–4);改动即保存。 */
export default function CapabilitiesPage() {
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [caps, setCaps] = useState<Map<string, number | null>>(new Map());
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  async function load() {
    const attrs = await api<Attribute[]>("/api/attributes");
    setAttributes(attrs);
    const list = await api<Capability[]>("/api/capabilities");
    setCaps(new Map(list.map((c) => [c.attribute, c.level])));
  }

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, []);

  async function save(name: string, value: string) {
    setSaved(false);
    try {
      if (value === "none") {
        await api(`/api/capabilities/${encodeURIComponent(name)}`, { method: "DELETE" });
      } else {
        await api("/api/capabilities", {
          method: "PUT",
          body: JSON.stringify({ attribute: name, level: value === "unrated" ? null : Number(value) }),
        });
      }
      await load();
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : t.caps.saveFailed);
    }
  }

  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        {t.caps.title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t.caps.hint}
      </Typography>
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}
      {saved && <Typography variant="caption" color="text.secondary" sx={{ mb: 1, display: "block" }}>{t.caps.saved}</Typography>}
      <Stack spacing={1}>
        {attributes.map((a) => {
          const has = caps.has(a.name);
          const level = caps.get(a.name);
          const value = !has ? "none" : level === null || level === undefined ? "unrated" : String(level);
          return (
            <Stack key={a.attributeId} direction="row" spacing={2} alignItems="center" justifyContent="space-between">
              <Typography sx={{ minWidth: 120 }}>{a.name}</Typography>
              <Select size="small" value={value} onChange={(e) => save(a.name, String(e.target.value))} sx={{ minWidth: 140 }}>
                <MenuItem value="none">{t.caps.none}</MenuItem>
                <MenuItem value="unrated">{a.leveled ? t.caps.unrated : t.caps.has}</MenuItem>
                {a.leveled &&
                  [1, 2, 3, 4].map((l) => (
                    <MenuItem key={l} value={String(l)}>{t.caps.level(l)}</MenuItem>
                  ))}
              </Select>
            </Stack>
          );
        })}
        {attributes.length === 0 && <Typography color="text.secondary">{t.caps.empty}</Typography>}
      </Stack>
      <Button variant="text" component={Link} href="/" sx={{ mt: 2 }}>
        {t.caps.back}
      </Button>
    </Container>
  );
}
