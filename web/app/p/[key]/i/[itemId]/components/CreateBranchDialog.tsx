"use client";

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useState, useEffect } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { Dropdown } from "@/components/Dropdown";
import { type Repo } from "../types";

/** CreateBranchDialog(YPJ-251 自 page.tsx 原样搬出,零语义变化) */
export function CreateBranchDialog({
  open,

  onClose,

  projectKey,

  itemNumber,

  itemType,

  itemTitle,

  itemId,

  onCreated,
}: {
  open: boolean;

  onClose: () => void;

  projectKey: string;

  itemNumber: string;

  itemType: string;

  itemTitle: string;

  itemId: string;

  onCreated: () => void;
}) {
  const [repos, setRepos] = useState<Repo[]>([]);

  const [repoId, setRepoId] = useState("");

  const [baseBranch, setBaseBranch] = useState("main");

  const [name, setName] = useState("");

  const [error, setError] = useState("");

  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;

    setError("");

    api<Repo[]>(`/api/projects/${projectKey}/repos`)
      .then((rs) => {
        setRepos(rs);

        setRepoId(rs[0]?.repoId ?? "");
      })

      .catch((e) => setError(e.message));

    // 预填 {type}/{票号[,依赖票号…]}[-英文slug] —— V19 YPJ-37 用户定案(spec 0036 v2);精确命名走 CLI 正路径

    void (async () => {
      const ids = [itemNumber];

      try {
        const deps = await api<{ dependencies: { number: string }[] }>(
          `/api/items/${itemId}/dependencies`,
        );

        ids.push(
          ...deps.dependencies.map((d) => d.number).filter((n) => n !== "?"),
        );
      } catch {
        // 依赖取不到 → 只用主票号,不阻塞预填
      }

      const asciiOnly = /^[A-Za-z0-9 -]+$/.test(itemTitle);

      const slug = asciiOnly
        ? itemTitle
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-+|-+$/g, "")
        : "";

      setName(`${itemType}/${ids.join(",")}${slug ? `-${slug}` : ""}`);
    })();
  }, [open, projectKey, itemId, itemNumber, itemType, itemTitle]);

  async function create() {
    setError("");

    setBusy(true);

    try {
      await api(`/api/items/${itemId}/branches`, {
        method: "POST",

        body: JSON.stringify({
          repoId,
          baseBranch: baseBranch.trim(),
          name: name.trim(),
        }),
      });

      onClose();

      onCreated();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.gh.branchTitle}</DialogTitle>

      <DialogContent>
        {error && (
          <Typography color="error" sx={{ mb: 1 }}>
            {error}
          </Typography>
        )}

        {repos.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {t.gh.repoEmpty}
          </Typography>
        )}

        <Stack spacing={2} sx={{ mt: 1 }}>
          <Dropdown
            label={t.gh.branchRepo}

            value={repoId}

            onChange={(v) => setRepoId(v)}

            options={repos.map((r) => ({ value: r.repoId, label: r.repo }))}
          />

          <TextField
            label={t.gh.branchBase}
            value={baseBranch}
            onChange={(e) => setBaseBranch(e.target.value)}
          />

          <TextField
            label={t.gh.branchName}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>{t.assignDialog.close}</Button>

        <Button
          variant="contained"
          onClick={create}
          disabled={busy || !repoId || !name.trim() || !baseBranch.trim()}
        >
          {t.gh.branchCreate}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
