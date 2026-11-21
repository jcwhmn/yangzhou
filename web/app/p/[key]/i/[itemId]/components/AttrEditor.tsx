"use client";

import {
  Button,
  IconButton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import { useState } from "react";
import { t } from "@/lib/texts";
import { Dropdown } from "@/components/Dropdown";
import { type Attribute, type AttrRow } from "../types";

/** AttrEditor(YPJ-251 自 page.tsx 原样搬出,零语义变化) */
export function AttrEditor({
  customAttrs,

  initial,

  onSave,
}: {
  customAttrs: Attribute[];

  initial: AttrRow[];

  onSave: (rows: AttrRow[]) => void;
}) {
  const [rows, setRows] = useState<AttrRow[]>(initial);

  return (
    <Stack spacing={1}>
      {rows.length === 0 && (
        <Typography color="text.secondary" variant="body2">
          {t.item.attrEmpty}
        </Typography>
      )}

      {rows.map((r, idx) => (
        <Stack key={idx} direction="row" spacing={1} alignItems="center">
          <Dropdown
            ariaLabel={t.item.itemAttributes}

            size="small"

            placeholder={t.item.itemAttributes}

            value={r.attribute}

            onChange={(v) =>
              setRows((prev) =>
                prev.map((x, i) => (i === idx ? { ...x, attribute: v } : x)),
              )
            }

            sx={{ minWidth: 200 }}

            options={customAttrs.map((at) => ({
              value: at.name,
              label: at.name,
            }))}
          />

          <TextField
            aria-label={t.item.itemAttributes}

            size="small"

            fullWidth

            value={r.value}

            onChange={(e) =>
              setRows((prev) =>
                prev.map((x, i) =>
                  i === idx ? { ...x, value: e.target.value } : x,
                ),
              )
            }
          />

          <IconButton
            onClick={() => setRows((prev) => prev.filter((_, i) => i !== idx))}
            aria-label="delete"
          >
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Stack>
      ))}

      <Stack direction="row" spacing={1}>
        <Button
          startIcon={<AddIcon />}
          onClick={() =>
            setRows((prev) => [...prev, { attribute: "", value: "" }])
          }
        >
          {t.item.attrAdd}
        </Button>

        <Button
          variant="contained"

          disabled={rows.some((r) => !r.attribute || !r.value)}

          onClick={() => onSave(rows.filter((r) => r.attribute && r.value))}
        >
          {t.item.save}
        </Button>
      </Stack>
    </Stack>
  );
}
