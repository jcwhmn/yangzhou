"use client";

import { useState } from "react";
import { Box, Button, Chip, Stack, Typography } from "@mui/material";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { CreateBranchDialog } from "./CreateBranchDialog";
import { type CommitBlock } from "../types";

/** GithubTab(YPJ-251 自 page.tsx 搬出;commits 群/branchOpen 按钮触发纯区块态,随组件内置) */
export function GithubTab({
  projectKey,

  itemId,

  gitRefs,

  itemNumber,

  itemType,

  itemTitle,

  onCreated,
}: {
  projectKey: string;

  itemId: string;

  gitRefs: {
    kind: string;
    repo: string;
    ref: string;
    url: string | null;
    state: string | null;
  }[];

  itemNumber: string;

  itemType: string;

  itemTitle: string;

  onCreated: () => void;
}) {
  const [commits, setCommits] = useState<CommitBlock[] | null>(null);

  const [commitsLoading, setCommitsLoading] = useState(false);

  const [commitsError, setCommitsError] = useState("");

  const [branchOpen, setBranchOpen] = useState(false);

  return (
    <Box>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h6">{t.gh.gitRefs}</Typography>

        <Button
          size="small"
          variant="outlined"
          onClick={() => setBranchOpen(true)}
        >
          {t.gh.branch}
        </Button>
      </Stack>

      {(gitRefs?.length ?? 0) === 0 && (
        <Typography color="text.secondary" variant="body2">
          {t.gh.gitRefsEmpty}
        </Typography>
      )}

      <Stack spacing={0.5}>
        {(gitRefs ?? []).map((r) => (
          <Stack
            key={`${r.kind}-${r.repo}-${r.ref}`}
            direction="row"
            spacing={1}
            alignItems="center"
          >
            <Chip
              size="small"

              variant="outlined"

              label={r.kind === "pr" ? t.gh.statePr : t.gh.stateBranch}

              color={r.kind === "pr" ? "secondary" : "default"}
            />

            {r.kind === "pr" && r.state && (
              <Chip
                size="small"

                label={
                  r.state === "merged"
                    ? t.gh.stateMerged
                    : r.state === "open"
                      ? t.gh.stateOpen
                      : t.gh.stateClosed
                }

                color={
                  r.state === "merged"
                    ? "success"
                    : r.state === "open"
                      ? "primary"
                      : "default"
                }
              />
            )}

            {r.url ? (
              <a href={r.url} target="_blank" rel="noreferrer">
                <Typography variant="body2">{r.ref}</Typography>
              </a>
            ) : (
              <Typography variant="body2">{r.ref}</Typography>
            )}

            <Typography variant="caption" color="text.secondary">
              {r.repo}
            </Typography>
          </Stack>
        ))}
      </Stack>

      <Stack
        direction="row"
        spacing={1}
        alignItems="center"
        sx={{ mt: 2, mb: 1 }}
      >
        <Typography variant="h6">{t.gh.commitsTitle}</Typography>

        <Button
          size="small"

          variant="outlined"

          onClick={() => {
            setCommitsError("");

            setCommitsLoading(true);

            api<CommitBlock[]>(`/api/items/${itemId}/commits`)
              .then(setCommits)

              .catch((e) =>
                setCommitsError(e instanceof Error ? e.message : "拉取失败"),
              )

              .finally(() => setCommitsLoading(false));
          }}

          disabled={commitsLoading}
        >
          {t.gh.loadCommits}
        </Button>
      </Stack>

      {commitsError && (
        <Typography color="error" variant="body2">
          {commitsError}
        </Typography>
      )}

      {commits === null && !commitsError && (
        <Typography color="text.secondary" variant="body2">
          {t.gh.commitsHint}
        </Typography>
      )}

      {(commits ?? []).map((block) => (
        <Box key={`${block.repo}-${block.ref}`} sx={{ mb: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {block.repo} · {block.ref}
            {block.viaPr && (
              <Typography
                component="span"
                variant="caption"
                color="text.secondary"
                sx={{ ml: 1, fontWeight: 400 }}
              >
                {t.gh.viaPr}
                {block.prNumber != null ? ` #${block.prNumber}` : ""}
              </Typography>
            )}
          </Typography>

          {block.commits.length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t.gh.noCommits}
            </Typography>
          )}

          {block.commits.map((c) => (
            <Typography
              key={c.sha}
              variant="caption"
              color="text.secondary"
              component="div"
            >
              {c.sha.slice(0, 7)} {c.message}
              {c.author ? ` · ${c.author}` : ""}
            </Typography>
          ))}
        </Box>
      ))}

      <CreateBranchDialog
        open={branchOpen}

        onClose={() => setBranchOpen(false)}

        projectKey={projectKey}

        itemNumber={itemNumber}

        itemType={itemType}

        itemTitle={itemTitle}

        itemId={itemId}

        onCreated={onCreated}
      />
    </Box>
  );
}
