"use client";

import {
  Button,
  Chip,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Typography,
} from "@mui/material";
import { t } from "@/lib/texts";
import { SignalChip, type Signal } from "@/components/Verdict";
import { type Item, type Sprint, type TypeWorkflow } from "../types";

/** SprintHeader(YPJ-252 自 page.tsx 搬出;rollup/文案派生内置,动作回调上行) */
export function SprintHeader({
  sprint,

  items,

  wf,

  l1Key,

  parentSel,

  typeName,

  onReparent,

  onAction,

  onOpenAdd,
}: {
  sprint: Sprint;

  items: Item[] | null;

  wf: TypeWorkflow[];

  l1Key: string | null;

  parentSel: {
    name: string;
    options: { sprintId: string; name: string }[];
  } | null;

  typeName: string | null;

  onReparent: (target: string) => void;

  onAction: (fn: string, target?: string) => void;

  onOpenAdd: () => void;
}) {
  function actionLabel(fn: string, target?: string): string {
    const name = typeName ?? sprint?.typeKey ?? "";
    // 已知函数名优先;自定义工作流的 switch 等未知函数按出边目标态取文案
    if (fn === "start") return t.sprintPage.startBtn.replace("{name}", name);
    if (fn === "complete")
      return t.sprintPage.completeBtn.replace("{name}", name);
    if (fn === "reopen") return t.sprintPage.reopenBtn;
    return target === "active"
      ? t.sprintPage.startBtn.replace("{name}", name)
      : target === "completed"
        ? t.sprintPage.completeBtn.replace("{name}", name)
        : target === "planned"
          ? t.sprintPage.reopenBtn
          : fn;
  }

  return (
    <>
      <Stack
        direction="row"
        spacing={2}
        alignItems="center"
        sx={{ mb: 1 }}
        flexWrap="wrap"
        useFlexGap
      >
        <Typography variant="h5">{sprint.name}</Typography>
        <Chip
          size="small"
          label={
            t.projectNav.sprintStatus[
              sprint.status as keyof typeof t.projectNav.sprintStatus
            ] ?? sprint.status
          }
          color={
            sprint.status === "active"
              ? "primary"
              : sprint.status === "completed"
                ? "default"
                : "warning"
          }
        />
        <Typography color="text.secondary" variant="body2">
          {sprint.startDate ?? "—"} ~ {sprint.endDate ?? "—"}
        </Typography>
        {/* spec 0113/0114:有上层类型才渲染;悬空组显示占位但不可选回(无置空语义) */}
        {parentSel && parentSel.options.length > 0 && (
          <FormControl size="small" sx={{ minWidth: 160 }}>
            <InputLabel id="epic-select-label">
              {t.sprintPage.parentLabel.replace("{name}", parentSel.name)}
            </InputLabel>
            <Select
              labelId="epic-select-label"
              label={t.sprintPage.parentLabel.replace("{name}", parentSel.name)}
              value={sprint.parentGroupId ?? ""}
              displayEmpty
              onChange={(e) => onReparent(e.target.value as string)}
            >
              {sprint.parentGroupId == null && (
                <MenuItem value="" disabled>
                  {t.sprintPage.parentDangling}
                </MenuItem>
              )}
              {parentSel.options.map((ep) => (
                <MenuItem key={ep.sprintId} value={ep.sprintId}>
                  {ep.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        )}
        {/* V14 组级 rollup:组内 items feasSignal 取最差(红>黄>绿;无信号不显示) */}
        {(() => {
          if (!items || items.length === 0) return null;
          const rank: Record<Signal, number> = {
            RED: 0,
            YELLOW: 1,
            GREEN: 2,
          };
          const sigs = items
            .map((i) => i.feasSignal)
            .filter((s): s is Signal => s !== null);
          return sigs.length > 0 ? (
            <SignalChip
              signal={sigs.reduce((a, b) => (rank[a] <= rank[b] ? a : b))}
              size="small"
            />
          ) : null;
        })()}
        <Stack
          direction="row"
          spacing={1}
          alignItems="center"
          sx={{ ml: "auto" }}
        >
          {wf
            .find((s) => s.name === sprint.status)
            ?.transitions.filter(
              (tr) => tr.function !== "backward" && sprint.typeKey === l1Key,
            )
            .map((tr) => (
              <Button
                key={tr.function}
                size="small"
                variant={
                  tr.function === "reopen" || tr.target === "planned"
                    ? "outlined"
                    : "contained"
                }
                color={
                  tr.function === "reopen" || tr.target === "planned"
                    ? "inherit"
                    : "primary"
                }
                disabled={items === null} // 0110:items 未载禁用,防误开处置/确认弹窗
                onClick={() => onAction(tr.function, tr.target)}
              >
                {actionLabel(tr.function, tr.target)}
              </Button>
            ))}
        </Stack>
        {sprint.status !== "completed" && sprint.typeKey === l1Key && (
          <Button size="small" variant="outlined" onClick={onOpenAdd}>
            拉入 item
          </Button>
        )}
      </Stack>
      {sprint.status === "completed" && (
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: "block", mb: 2 }}
        >
          此 Sprint 已完成,以下为历史 items(成员关系不可再变,可重开组)。
        </Typography>
      )}

      {items !== null && items.length === 0 && (
        <Typography color="text.secondary" sx={{ mt: 4 }}>
          此 Sprint 还没有 item——点右上「拉入 item」批量勾选,或在 item
          详情用「Sprint」下拉指派。
        </Typography>
      )}
    </>
  );
}
