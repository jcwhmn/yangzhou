"use client";

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import { useState } from "react";
import { t } from "@/lib/texts";
import { Dropdown } from "@/components/Dropdown";
import { type Attribute, type ReqRow } from "../types";

/** RequirementsDialog(YPJ-251 自 page.tsx 原样搬出,零语义变化) */
export function RequirementsDialog({
  open,

  onClose,

  onSave,

  attributes,
}: {
  open: boolean;

  onClose: () => void;

  onSave: (rows: ReqRow[]) => void;

  attributes: Attribute[];
}) {
  const [rows, setRows] = useState<ReqRow[]>([
    { attribute: "", minLevel: null },
  ]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.item.requirements}</DialogTitle>

      <DialogContent>
        <Stack spacing={1}>
          {rows.map((r, idx) => (
            <Stack key={idx} direction="row" spacing={1} alignItems="center">
              <Dropdown
                ariaLabel={t.item.attribute}

                size="small"

                placeholder={t.item.attribute}

                value={r.attribute}

                onChange={(v) =>
                  setRows((prev) =>
                    prev.map((x, i) =>
                      i === idx ? { ...x, attribute: v } : x,
                    ),
                  )
                }

                sx={{ minWidth: 200 }}

                options={attributes.map((a) => ({
                  value: a.name,
                  label: a.name,
                }))}
              />

              <Dropdown
                ariaLabel={t.item.none}

                size="small"

                placeholder={t.item.none}

                value={r.minLevel == null ? "" : String(r.minLevel)}

                onChange={(v) =>
                  setRows((prev) =>
                    prev.map((x, i) =>
                      i === idx
                        ? { ...x, minLevel: v === "" ? null : Number(v) }
                        : x,
                    ),
                  )
                }

                sx={{ minWidth: 160 }}

                options={[
                  { value: "", label: t.item.none },
                  ...[1, 2, 3, 4].map((l) => ({
                    value: String(l),
                    label: `≥${l}`,
                  })),
                ]}
              />

              <IconButton
                onClick={() =>
                  setRows((prev) => prev.filter((_, i) => i !== idx))
                }
                aria-label="delete"
              >
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}

          <Button
            startIcon={<AddIcon />}
            onClick={() =>
              setRows((prev) => [...prev, { attribute: "", minLevel: null }])
            }
          >
            {t.item.addRequirement}
          </Button>
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>{t.assignDialog.close}</Button>

        <Button
          variant="contained"
          onClick={() => onSave(rows.filter((r) => r.attribute))}
        >
          {t.item.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
