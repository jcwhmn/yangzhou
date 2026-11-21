"use client";

import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { type Item } from "../types";

/** SubitemsBlock(YPJ-251 自 page.tsx 搬出;直排区块+YPJ-218 策略弹窗随迁,态留 page(动作/主表单共用),动作回调上行,testids 保留) */
export function SubitemsBlock({
  projectKey,

  subitems,

  newSubTitle,

  onSubTitleChange,

  onAdd,

  policyOpen,

  onPolicyClose,

  onPolicyIgnore,

  onPolicyMigrate,
}: {
  projectKey: string;

  subitems: Item[] | null;

  newSubTitle: string;

  onSubTitleChange: (v: string) => void;

  onAdd: () => void;

  policyOpen: boolean;

  onPolicyClose: () => void;

  onPolicyIgnore: () => void;

  onPolicyMigrate: () => void;
}) {
  return (
    <>
      <Box sx={{ mt: 3 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>
          子条目
        </Typography>

        {(subitems ?? []).length === 0 ? (
          <Typography variant="caption" color="text.secondary">
            (无子条目)
          </Typography>
        ) : (
          <Stack spacing={0.5}>
            {(subitems ?? []).map((s) => (
              <Stack
                key={s.itemId}
                direction="row"
                spacing={1}
                alignItems="center"
              >
                <Chip size="small" variant="outlined" label={s.number} />

                <Link href={`/p/${projectKey}/i/${s.itemId}`}>
                  <Typography variant="body2" noWrap sx={{ maxWidth: 360 }}>
                    {s.title}
                  </Typography>
                </Link>

                <Chip
                  size="small"
                  label={s.status}
                  color={s.overdue ? "error" : "default"}
                />
              </Stack>
            ))}
          </Stack>
        )}

        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
          <TextField
            size="small"

            placeholder="加一个子条目…"

            value={newSubTitle}

            onChange={(e) => onSubTitleChange(e.target.value)}

            sx={{ width: 300 }}
          />

          <Button
            size="small"
            variant="outlined"
            onClick={onAdd}
            disabled={!newSubTitle.trim()}
          >
            添加
          </Button>
        </Stack>
      </Box>

      {/* YPJ-218 spec 0139:加子时父自有时间线策略(默认迁移) */}
      <Dialog open={policyOpen} onClose={onPolicyClose}>
        <DialogTitle>父 item 已有自己的时间线</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            父 item 的起止时间将让给子项汇总。选择如何处置原有时间线:
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={onPolicyClose}>取消</Button>
          <Button onClick={onPolicyIgnore} data-testid="sub-policy-ignore">
            忽略原时间线
          </Button>
          <Button
            variant="contained"
            onClick={onPolicyMigrate}
            data-testid="sub-policy-migrate"
          >
            迁移为新子项(推荐)
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
