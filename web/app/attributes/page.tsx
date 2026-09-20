"use client";

import {
  Box,
  Button,
  Chip,
  Container,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Attribute = {
  attributeId: string;
  name: string;
  kind: string;
  leveled: boolean;
  parentId: number | null;
  categoryName: string | null;
};

const UNCATEGORIZED = "未分类";

/** 词表管理(V10-S1 两层层次:分类 → 技能/标签;leveled 切换不删既有等级数据——休眠待唤醒)。 */
export default function AttributesPage() {
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [name, setName] = useState("");
  const [kind, setKind] = useState("skill");
  const [parentId, setParentId] = useState<string>("");
  const [leveled, setLeveled] = useState(true);
  const [error, setError] = useState("");

  async function load() {
    const all = await api<Attribute[]>("/api/attributes");
    setAttributes(all);
    // 兜底:确保分类数组可用(分组渲染用)
    if (!categories.length) {
      // 由 attributes 推导(kind=category),不额外请求
    }
  }

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, []);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      const body: Record<string, unknown> = { name: name.trim(), kind, leveled: kind === "skill" && leveled };
      if (kind !== "category" && parentId) body.parentId = parentId;
      await api("/api/attributes", { method: "POST", body: JSON.stringify(body) });
      setName("");
      setParentId("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.attrs.createFailed);
    }
  }

  async function patch(a: Attribute, changes: Partial<Pick<Attribute, "kind" | "leveled" | "parentId">>) {
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

  const categories = attributes.filter((a) => a.kind === "category");
  const leaves = attributes.filter((a) => a.kind !== "category");
  const byGroup = new Map<string, Attribute[]>();
  leaves.forEach((a) => {
    const g = a.categoryName ?? UNCATEGORIZED;
    byGroup.set(g, [...(byGroup.get(g) ?? []), a]);
  });
  const groups = [...byGroup.entries()].sort((a, b) => (a[0] === UNCATEGORIZED ? 1 : b[0] === UNCATEGORIZED ? -1 : a[0].localeCompare(b[0])));

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        {t.attrs.title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t.attrs.hint}
      </Typography>
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}

      {/* 创建表单 */}
      <Stack component="form" direction="row" spacing={1} onSubmit={create} sx={{ mb: 3 }} alignItems="center" flexWrap="wrap" useFlexGap>
        <TextField size="small" label={t.attrs.name} value={name} onChange={(e) => setName(e.target.value)} required />
        <TextField select size="small" label="类型" value={kind} onChange={(e) => { setKind(e.target.value); setParentId(""); }} sx={{ width: 150 }}>
          <MenuItem value="skill">{t.attrs.kindSkill}</MenuItem>
          <MenuItem value="label">{t.attrs.kindLabel}</MenuItem>
          <MenuItem value="category">分类</MenuItem>
        </TextField>
        {kind !== "category" && (
          <TextField select size="small" label="分类" value={parentId} onChange={(e) => setParentId(e.target.value)} sx={{ width: 150 }}>
            <MenuItem value="">{UNCATEGORIZED}</MenuItem>
            {categories.map((c) => (
              <MenuItem key={c.attributeId} value={c.attributeId}>
                {c.name}
              </MenuItem>
            ))}
          </TextField>
        )}
        {kind === "skill" && (
          <FormControlLabel control={<Switch checked={leveled} onChange={(e) => setLeveled(e.target.checked)} />} label={t.attrs.leveled} />
        )}
        <Button type="submit" variant="contained">
          {t.attrs.add}
        </Button>
      </Stack>

      {/* 分组列表 */}
      {groups.map(([group, attrs]) => (
        <Box key={group} sx={{ mb: 3 }}>
          <Typography variant="subtitle1" gutterBottom>
            {group}
          </Typography>
          <Stack spacing={1}>
            {attrs.map((a) => (
              <Stack key={a.attributeId} direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography sx={{ minWidth: 110 }}>{a.name}</Typography>
                  <Chip size="small" label={a.kind} variant="outlined" />
                </Stack>
                <Stack direction="row" spacing={1} alignItems="center">
                  {a.kind !== "category" && (
                    <>
                      <Button size="small" onClick={() => patch(a, { kind: a.kind === "skill" ? "label" : "skill" })}>
                        {t.attrs.toKind(a.kind === "skill" ? "label" : "skill")}
                      </Button>
                      <Button size="small" disabled={a.kind !== "skill"} onClick={() => patch(a, { leveled: !a.leveled })}>
                        {a.leveled ? t.attrs.unleveled : t.attrs.leveled}
                      </Button>
                    </>
                  )}
                  <Button size="small" color="error" onClick={() => remove(a)}>
                    {t.attrs.delete}
                  </Button>
                </Stack>
              </Stack>
            ))}
          </Stack>
        </Box>
      ))}
    </Container>
  );
}
