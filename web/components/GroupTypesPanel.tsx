"use client";

// YPJ-48(spec 0056)最小前端:项目设置页的 group 类型管理(列/增/删)。
// 改名走 API 不做 UI(手工验证经 api.http);sprint/release 的组管理在专属功能里。
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, List, ListItemText, Stack, TextField, Typography } from "@mui/material";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type GroupType = { typeKey: string; name: string; builtin: boolean };

export function GroupTypesPanel({ projectKey, open, onClose }: { projectKey: string; open: boolean; onClose: () => void }) {
  const [types, setTypes] = useState<GroupType[]>([]);
  const [typeKey, setTypeKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  const load = () => {
    api<GroupType[]>(`/api/projects/${projectKey}/group-types`).then(setTypes).catch(() => setTypes([]));
  };

  useEffect(() => {
    if (open) {
      load();
      setTypeKey("");
      setName("");
      setError("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  async function create() {
    try {
      await api(`/api/projects/${projectKey}/group-types`, {
        method: "POST",
        body: JSON.stringify({ typeKey: typeKey.trim(), name: name.trim() }),
      });
      setTypeKey("");
      setName("");
      setError("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.groupTypes.failed);
    }
  }

  async function remove(typeKey: string) {
    try {
      await api(`/api/projects/${projectKey}/group-types/${typeKey}`, { method: "DELETE" });
      setError("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.groupTypes.failed);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t.groupTypes.title}</DialogTitle>
      <DialogContent>
        <Typography variant="caption" color="text.secondary">{t.groupTypes.hint}</Typography>
        <List dense>
          {types.map((g) => (
            <Box key={g.typeKey} component="li" sx={{ display: "flex", alignItems: "center" }}>
              <ListItemText
                primary={g.name}
                secondary={g.typeKey}
                sx={{ flex: 1 }}
              />
              {g.builtin && <Chip size="small" label={t.groupTypes.builtin} sx={{ mr: 1 }} />}
              {!g.builtin && (
                <IconButton size="small" aria-label={t.groupTypes.deleteBtn} onClick={() => remove(g.typeKey)}>
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              )}
            </Box>
          ))}
          {types.length === 0 && <Typography variant="body2" color="text.secondary">{t.groupTypes.empty}</Typography>}
        </List>
        <Stack direction="row" spacing={1} sx={{ mt: 1 }} component="form" onSubmit={(e) => { e.preventDefault(); if (typeKey.trim() && name.trim()) create(); }}>
          <TextField size="small" label={t.groupTypes.typeKey} value={typeKey} onChange={(e) => setTypeKey(e.target.value)} sx={{ width: 150 }} />
          <TextField size="small" label={t.groupTypes.name} value={name} onChange={(e) => setName(e.target.value)} sx={{ flex: 1 }} />
          <Button type="submit" variant="contained" disabled={!typeKey.trim() || !name.trim()}>{t.groupTypes.create}</Button>
        </Stack>
        {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.groupTypes.close}</Button>
      </DialogActions>
    </Dialog>
  );
}
