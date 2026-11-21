"use client";

import { Box, Stack, Typography } from "@mui/material";
import { activityLabel, type Activity } from "../types";

/** ActivityTab(YPJ-251 自 page.tsx 搬出,零语义变化) */
export function ActivityTab({
  activity,

  memberNames,
}: {
  activity: Activity[];

  memberNames: Map<string, string>;
}) {
  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="h6" gutterBottom>
        活动日志
      </Typography>

      <Stack spacing={0.5}>
        {activity.map((a) => (
          <Typography key={a.objectId} variant="caption" color="text.secondary">
            {new Date(a.createdAt).toLocaleString("zh-CN")} ·{" "}
            {a.actorMemberId == null
              ? "GitHub"
              : (memberNames.get(String(a.actorMemberId)) ??
                `#${a.actorMemberId}`)}{" "}
            · {activityLabel[a.kind] ?? a.kind}
            {a.oldValue || a.newValue
              ? `: ${a.oldValue ?? ""} → ${a.newValue ?? ""}`
              : ""}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
