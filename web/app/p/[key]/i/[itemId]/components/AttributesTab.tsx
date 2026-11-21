"use client";

import { Box, Typography } from "@mui/material";
import { t } from "@/lib/texts";
import { AttrEditor } from "./AttrEditor";
import { type Attribute, type AttrRow } from "../types";

/** AttributesTab(YPJ-251 自 page.tsx 搬出,零语义变化) */
export function AttributesTab({
  attributes,

  itemAttributes,

  onSaveAttrs,
}: {
  attributes: Attribute[];

  itemAttributes: AttrRow[] | null;

  onSaveAttrs: (rows: AttrRow[]) => void;
}) {
  return (
    <Box>
      <Typography variant="h6" sx={{ mb: 1 }}>
        {t.item.itemAttributes}
      </Typography>

      <AttrEditor
        customAttrs={attributes.filter((at) => at.kind === "custom")}

        initial={(itemAttributes ?? []).map((at) => ({ ...at }))}

        onSave={onSaveAttrs}
      />
    </Box>
  );
}
