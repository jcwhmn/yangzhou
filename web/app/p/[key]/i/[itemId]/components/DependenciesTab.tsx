"use client";

import { useState } from "react";
import { Box, Button, Chip, Stack, TextField, Typography } from "@mui/material";
import { DependencyAddForm } from "./DependencyAddForm";
import { type Checklist, type Dep } from "../types";

/** DependenciesTab(YPJ-251 自 page.tsx 搬出;deps/checklist 群态留 page(首屏加载/动作依赖),depAddOpen 纯 UI 内置) */
export function DependenciesTab({
  projectKey,

  itemId,

  deps,

  depBlocked,

  checklist,

  newCheckText,

  onCheckTextChange,

  onAddCheck,

  onToggleCheck,

  onRemoveCheck,

  onAddDep,

  onRemoveDep,
}: {
  projectKey: string;

  itemId: string;

  deps: Dep[] | null;

  depBlocked: boolean;

  checklist: Checklist | null;

  newCheckText: string;

  onCheckTextChange: (v: string) => void;

  onAddCheck: () => void;

  onToggleCheck: (entryId: string, done: boolean) => void;

  onRemoveCheck: (entryId: string) => void;

  onAddDep: (dependsOnItemId: string) => void;

  onRemoveDep: (rowId: string) => void;
}) {
  const [depAddOpen, setDepAddOpen] = useState(false);

  return (
    <>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">检查清单</Typography>

          {checklist && checklist.totalCount > 0 && (
            <Chip
              size="small"
              label={`${checklist.doneCount}/${checklist.totalCount}`}
              color={
                checklist.doneCount === checklist.totalCount
                  ? "success"
                  : "default"
              }
            />
          )}
        </Stack>

        <Stack spacing={0.5}>
          {(checklist?.entries ?? []).map((e) => (
            <Stack
              key={e.checklistItemId}
              direction="row"
              spacing={1}
              alignItems="center"
            >
              <input
                type="checkbox"

                checked={e.done}

                onChange={() => onToggleCheck(e.checklistItemId, !e.done)}
              />

              <Typography
                variant="body2"
                sx={{
                  textDecoration: e.done ? "line-through" : "none",
                  color: e.done ? "text.secondary" : "inherit",
                }}
              >
                {e.text}
              </Typography>

              <Button
                size="small"
                color="error"
                onClick={() => onRemoveCheck(e.checklistItemId)}
              >
                删除
              </Button>
            </Stack>
          ))}
        </Stack>

        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
          <TextField
            size="small"

            placeholder="加一条检查项…"

            value={newCheckText}

            onChange={(e) => onCheckTextChange(e.target.value)}

            sx={{ width: 300 }}
          />

          <Button
            size="small"
            variant="outlined"
            onClick={onAddCheck}
            disabled={!newCheckText.trim()}
          >
            添加
          </Button>
        </Stack>
      </Box>

      <Box sx={{ mt: 3 }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">依赖</Typography>

          {depBlocked && <Chip size="small" color="error" label="⛔ 被阻塞" />}

          <Button
            size="small"
            variant="outlined"
            onClick={() => setDepAddOpen((v) => !v)}
          >
            加依赖
          </Button>
        </Stack>

        {depAddOpen && (
          <DependencyAddForm
            projectKey={projectKey}
            selfId={itemId}
            onAdd={onAddDep}
          />
        )}

        {(deps ?? []).length === 0 ? (
          <Typography variant="caption" color="text.secondary">
            (无依赖)
          </Typography>
        ) : (
          <Stack spacing={0.5}>
            {(deps ?? []).map((d) => (
              <Stack
                key={d.dependencyItemId}
                direction="row"
                spacing={1}
                alignItems="center"
              >
                <Chip size="small" variant="outlined" label={d.number} />

                <Typography variant="caption" noWrap sx={{ maxWidth: 220 }}>
                  {d.title}
                </Typography>

                <Chip
                  size="small"
                  label={d.statusName}
                  color={d.final ? "success" : "default"}
                />

                {!d.final && <Chip size="small" color="error" label="阻塞中" />}

                <Button
                  size="small"
                  color="error"
                  onClick={() => onRemoveDep(d.dependencyItemId)}
                >
                  移除
                </Button>
              </Stack>
            ))}
          </Stack>
        )}
      </Box>
    </>
  );
}
