"use client";

import { Button, Stack } from "@mui/material";
import { useState, useEffect } from "react";
import { api } from "@/lib/api";

/** DependencyAddForm(YPJ-251 自 page.tsx 原样搬出,零语义变化) */
export function DependencyAddForm({
  projectKey,

  selfId,

  onAdd,
}: {
  projectKey: string;

  selfId: string;

  onAdd: (itemId: string) => void;
}) {
  const [items, setItems] = useState<
    { itemId: string; number: string; title: string }[]
  >([]);

  const [pick, setPick] = useState("");

  useEffect(() => {
    api<{ itemId: string; number: string; title: string }[]>(
      `/api/projects/${projectKey}/items`,
    )
      .then((list) => setItems(list.filter((i) => i.itemId !== selfId)))

      .catch(() => setItems([]));
  }, [projectKey, selfId]);

  return (
    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
      <select
        aria-label="被依赖 item"

        value={pick}

        onChange={(e) => setPick(e.target.value)}

        style={{ padding: "6px", borderRadius: 8 }}
      >
        <option value="">选择被依赖 item…</option>

        {items.map((i) => (
          <option key={i.itemId} value={i.itemId}>
            {i.number} {i.title}
          </option>
        ))}
      </select>

      <Button
        size="small"
        variant="contained"
        disabled={!pick}
        onClick={() => onAdd(pick)}
        aria-label="确认添加依赖"
      >
        添加
      </Button>
    </Stack>
  );
}
