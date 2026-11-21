"use client";

import { Box, Button, Stack, Typography } from "@mui/material";
import { Dropdown } from "@/components/Dropdown";
import { t } from "@/lib/texts";
import type { ViewDto } from "../shared";

/** 工具栏:标题+视图切换(YPJ-47)+保存/另存/删除;视图机器(applyView/重置/直存决策)留 page 走回调。 */
export default function TableToolbar({
  projectKey,
  selectedView,
  views,
  selectedName,
  dirty,
  loaded,
  onViewChange,
  onSave,
  onSaveAs,
  onDelete,
}: {
  projectKey: string;
  selectedView: string | null;
  views: ViewDto[];
  selectedName: string;
  dirty: boolean;
  loaded: boolean;
  onViewChange: (viewId: string) => void;
  onSave: () => void;
  onSaveAs: () => void;
  onDelete: () => void;
}) {
  return (
    <Stack
      direction="row"
      spacing={2}
      alignItems="center"
      sx={{ mb: 2 }}
      flexWrap="wrap"
      useFlexGap
    >
      <Typography variant="h5">{`${String(projectKey)} · 表格`}</Typography>
      <Box sx={{ flex: 1 }} />
      {/* 视图控件(YPJ-47) */}
      <Dropdown
        ariaLabel={t.view.name}
        size="small"
        value={selectedView ?? ""}
        onChange={onViewChange}
        sx={{ width: 180 }}
        options={[
          { value: "", label: t.view.defaultView },
          ...views.map((v) => ({ value: v.viewId, label: v.name })),
        ]}
      />
      <Button
        size="small"
        variant={dirty ? "contained" : "outlined"}
        disabled={!loaded}
        onClick={onSave}
      >
        {t.view.save}
        {dirty ? " ·" : ""}
      </Button>
      <Button size="small" onClick={onSaveAs} disabled={!loaded}>
        {t.view.saveAs}
      </Button>
      {selectedView && (
        <Button
          size="small"
          color="error"
          disabled={!loaded}
          onClick={onDelete}
        >
          {t.view.delete}
        </Button>
      )}
    </Stack>
  );
}
