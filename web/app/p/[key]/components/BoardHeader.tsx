"use client";

import {
  Button,
  IconButton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import RefreshIcon from "@mui/icons-material/Refresh";
import { createPortal } from "react-dom";
import { useProjectToolbarSlot } from "@/components/ProjectToolbar";
import { EstimateField } from "@/components/EstimateField";
import { t } from "@/lib/texts";
import type { EstimateScale } from "@/lib/estimate";

/** 头部:标题+手刷+快速添加 portal(YPJ-187;表单态留 page,此处受控——提交/禁用逻辑随 onAddItem/loading 上行)。 */
export default function BoardHeader({
  projectKey,
  loading,
  estRequired,
  estScales,
  newTitle,
  onTitleChange,
  estValue,
  estUnit,
  onEstChange,
  onAddItem,
  onRefresh,
}: {
  projectKey: string;
  loading: boolean;
  estRequired: boolean;
  estScales: EstimateScale[];
  newTitle: string;
  onTitleChange: (title: string) => void;
  estValue: string;
  estUnit: string;
  onEstChange: (value: string, unit: string) => void;
  onAddItem: (e: React.FormEvent) => void;
  onRefresh: () => void;
}) {
  const { slotRef } = useProjectToolbarSlot();
  return (
    <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
      <Typography variant="h5">
        {String(projectKey).toUpperCase()} · 看板
      </Typography>
      <IconButton size="small" aria-label={t.board.refresh} onClick={onRefresh}>
        <RefreshIcon fontSize="small" />
      </IconButton>
      {/* YPJ-187:快速添加 portal 进统一顶栏;工时/收藏/设置在顶栏右簇 */}
      {slotRef.current &&
        createPortal(
          <Stack
            component="form"
            direction="row"
            spacing={1}
            onSubmit={onAddItem}
          >
            <TextField
              size="small"
              placeholder={t.board.addItem}
              value={newTitle}
              onChange={(e) => onTitleChange(e.target.value)}
            />
            <EstimateField
              scales={estScales}
              required={estRequired}
              value={estValue}
              unit={estUnit}
              onChange={(v, u) => onEstChange(v, u)}
            />
            <Button
              type="submit"
              variant="outlined"
              disabled={
                loading ||
                (estUnit !== "" &&
                  (estValue === "" || Number(estValue) <= 0)) ||
                (estRequired && estValue === "")
              }
            >
              {t.board.addItem}
            </Button>
          </Stack>,
          slotRef.current,
        )}
    </Stack>
  );
}
