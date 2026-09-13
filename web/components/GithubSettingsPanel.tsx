"use client";

import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type Repo = { repoId: string; repo: string };
type Status = { statusId: string; name: string; position: number };
type Rule = { eventType: string; statusId: string | null; statusName: string | null };

const EVENT_LABELS: Record<string, string> = {
  branch_created: t.gh.ruleBranch,
  pr_opened: t.gh.rulePrOpen,
  pr_merged: t.gh.rulePrMerged,
  pr_closed_unmerged: t.gh.rulePrClosed,
};
const EVENT_ORDER = ["branch_created", "pr_opened", "pr_merged", "pr_closed_unmerged"];

/** 项目级 GitHub 集成设置(V6-S4):仓库挂载 + 事件→状态映射 4 槽。 */
export function GithubSettingsPanel({
  projectKey,
  open,
  onClose,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
}) {
  const [repos, setRepos] = useState<Repo[]>([]);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [rules, setRules] = useState<Record<string, string | null>>({});
  const [newRepo, setNewRepo] = useState("");
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  const load = useCallback(async () => {
    try {
      const [rs, project, rl] = await Promise.all([
        api<Repo[]>(`/api/projects/${projectKey}/repos`),
        api<{ statuses: Status[] }>(`/api/projects/${projectKey}`),
        api<Rule[]>(`/api/projects/${projectKey}/workflow-rules`),
      ]);
      setRepos(rs);
      setStatuses(project.statuses);
      setRules(Object.fromEntries(rl.map((r) => [r.eventType, r.statusId])));
    } catch (e) {
      setError(e instanceof Error ? e.message : t.gh.loadFailed);
    }
  }, [projectKey]);

  useEffect(() => {
    if (open) {
      setError("");
      load();
    }
  }, [open, load]);

  async function addRepo(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await api(`/api/projects/${projectKey}/repos`, {
        method: "POST",
        body: JSON.stringify({ repo: newRepo.trim() }),
      });
      setNewRepo("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "挂载失败");
    }
  }

  async function removeRepo(repoId: string) {
    setError("");
    try {
      await api(`/api/projects/${projectKey}/repos/${repoId}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function saveRules() {
    setError("");
    try {
      const body = EVENT_ORDER.filter((ev) => rules[ev]).map((ev) => ({
        eventType: ev,
        statusId: rules[ev],
      }));
      await api(`/api/projects/${projectKey}/workflow-rules`, {
        method: "PUT",
        body: JSON.stringify({ rules: body }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 1500);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.gh.panelTitle}</DialogTitle>
      <DialogContent>
        {error && (
          <Typography color="error" sx={{ mb: 1 }}>
            {error}
          </Typography>
        )}

        <Typography variant="subtitle2" sx={{ mt: 1, mb: 1 }}>
          {t.gh.repos}
        </Typography>
        {repos.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {t.gh.repoEmpty}
          </Typography>
        )}
        <Stack spacing={0.5} sx={{ mb: 2 }}>
          {repos.map((r) => (
            <Stack key={r.repoId} direction="row" spacing={1} alignItems="center">
              <Chip label={`🐙 ${r.repo}`} />
              <IconButton size="small" color="error" onClick={() => removeRepo(r.repoId)} aria-label="delete">
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}
        </Stack>
        <Stack component="form" direction="row" spacing={1} onSubmit={addRepo} sx={{ mb: 3 }}>
          <TextField
            size="small"
            placeholder={t.gh.repoInput}
            value={newRepo}
            onChange={(e) => setNewRepo(e.target.value)}
            sx={{ width: 260 }}
          />
          <Button type="submit" variant="outlined" disabled={!newRepo.trim()}>
            {t.gh.repoAdd}
          </Button>
        </Stack>

        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          {t.gh.rules}
        </Typography>
        <Stack spacing={1}>
          {EVENT_ORDER.map((ev) => (
            <Stack key={ev} direction="row" spacing={1} alignItems="center">
              <Typography variant="body2" sx={{ width: 140 }}>
                {EVENT_LABELS[ev]}
              </Typography>
              <Select
                size="small"
                value={rules[ev] ?? ""}
                onChange={(e) => setRules((prev) => ({ ...prev, [ev]: String(e.target.value) || null }))}
                sx={{ minWidth: 200 }}
                displayEmpty
              >
                <MenuItem value="">{t.gh.ruleNone}</MenuItem>
                {statuses.map((s) => (
                  <MenuItem key={s.statusId} value={s.statusId}>
                    {s.name}
                  </MenuItem>
                ))}
              </Select>
            </Stack>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions>
        {saved && (
          <Typography variant="body2" color="text.secondary" sx={{ mr: 1 }}>
            {t.gh.rulesSaved}
          </Typography>
        )}
        <Button onClick={onClose}>{t.assignDialog.close}</Button>
        <Button variant="contained" onClick={saveRules}>
          {t.item.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
