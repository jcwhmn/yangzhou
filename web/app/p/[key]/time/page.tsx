"use client";

import { Box, Card, CardContent, Container, Stack, Typography } from "@mui/material";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type MemberSummary = {
  memberId: string;
  displayName: string;
  totalMinutes: number;
  entryCount: number;
};

/** V8-S4:项目工时按人聚合报表(只读;录入入口在 item 详情)。 */
export default function TimeSummaryPage() {
  const { key } = useParams<{ key: string }>();
  const [summary, setSummary] = useState<MemberSummary[] | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setSummary(await api<MemberSummary[]>(`/api/projects/${key}/time-summary`));
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }, [key]);

  useEffect(() => {
    load().catch((e) => setError(e instanceof Error ? e.message : t.time.failed));
  }, [load]);

  const projectTotal = (summary ?? []).reduce((acc, m) => acc + m.totalMinutes, 0);

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{t.time.title(String(key))}</Typography>
        {projectTotal > 0 && (
          <Typography variant="h6" color="primary">
            {t.time.total}:{t.time.fmt(projectTotal)}
          </Typography>
        )}
      </Stack>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {summary !== null && summary.length === 0 && (
        <Typography color="text.secondary">{t.time.empty}</Typography>
      )}
      <Stack spacing={1}>
        {(summary ?? []).map((m) => (
          <Card key={m.memberId} variant="outlined">
            <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
              <Stack direction="row" spacing={2} alignItems="center">
                <Typography variant="subtitle1">{m.displayName}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ ml: "auto" }}>
                  {t.time.fmt(m.totalMinutes)} · {t.time.entries} {m.entryCount}
                </Typography>
              </Stack>
              <Box sx={{ mt: 1, height: 6, borderRadius: 1, bgcolor: "action.hover" }}>
                {projectTotal > 0 && m.totalMinutes > 0 && (
                  <Box
                    sx={{
                      height: 6,
                      borderRadius: 1,
                      width: `${Math.round((m.totalMinutes / projectTotal) * 100)}%`,
                      bgcolor: "primary.main",
                    }}
                  />
                )}
              </Box>
            </CardContent>
          </Card>
        ))}
      </Stack>
    </Container>
  );
}
