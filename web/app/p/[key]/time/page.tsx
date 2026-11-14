"use client";

import {
  Box,
  Button,
  Card,
  CardContent,
  CardHeader,
  Stack,
  Typography,
} from "@mui/material";
import { BarChart } from "@mui/x-charts/BarChart";
import { LineChart } from "@mui/x-charts/LineChart";
import { PageShell } from "@/components/PageShell";
import Link from "next/link";
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

type TypeMinutes = {
  typeKey: string;
  typeName: string;
  minutes: number;
  entryCount: number;
};

type DayMinutes = {
  date: string;
  minutes: number;
};

type TimeReport = {
  byMember: MemberSummary[];
  byType: TypeMinutes[];
  byDay: DayMinutes[];
};

/** V8-S4 项目工时按人聚合 + YPJ-179(spec 0124)报表三图;录入入口在 item 详情。 */
export default function TimeSummaryPage() {
  const { key } = useParams<{ key: string }>();
  const [report, setReport] = useState<TimeReport | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setReport(await api<TimeReport>(`/api/projects/${key}/time-report`));
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }, [key]);

  useEffect(() => {
    load().catch((e) =>
      setError(e instanceof Error ? e.message : t.time.failed),
    );
  }, [load]);

  const summary = report?.byMember ?? null;
  const projectTotal = (summary ?? []).reduce(
    (acc, m) => acc + m.totalMinutes,
    0,
  );

  return (
    <PageShell>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Button size="small" component={Link} href={`/p/${key}`}>
          ← 看板
        </Button>
        <Typography variant="h5">{t.time.title(String(key))}</Typography>
        {projectTotal > 0 && (
          <Typography
            variant="h6"
            sx={{ color: "text.primary", fontWeight: 600 }}
          >
            {t.time.total}:{t.time.fmt(projectTotal)}
          </Typography>
        )}
      </Stack>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {report !== null &&
        report.byMember.length === 0 &&
        report.byType.length === 0 &&
        report.byDay.length === 0 && (
          <Typography color="text.secondary">{t.time.empty}</Typography>
        )}
      <Stack spacing={1}>
        {(summary ?? []).map((m) => (
          <Card key={m.memberId} variant="outlined">
            <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
              <Stack direction="row" spacing={2} alignItems="center">
                <Typography variant="subtitle1">{m.displayName}</Typography>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{ ml: "auto" }}
                >
                  {t.time.fmt(m.totalMinutes)} · {t.time.entries} {m.entryCount}
                </Typography>
              </Stack>
              <Box
                sx={{
                  mt: 1,
                  height: 6,
                  borderRadius: 1,
                  bgcolor: "action.hover",
                }}
              >
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
      {report !== null && projectTotal > 0 && (
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" },
            gap: 2,
            mt: 2,
          }}
        >
          <ChartCard title={t.time.byMember}>
            <BarChart
              height={280}
              xAxis={[
                {
                  scaleType: "band",
                  data: report.byMember.map((m) => m.displayName),
                },
              ]}
              series={[
                {
                  label: t.time.minutesLabel,
                  data: report.byMember.map((m) => m.totalMinutes),
                },
              ]}
            />
          </ChartCard>
          <ChartCard title={t.time.byTypeChart}>
            <BarChart
              height={280}
              xAxis={[
                {
                  scaleType: "band",
                  data: report.byType.map((x) => x.typeName),
                },
              ]}
              series={[
                {
                  label: t.time.minutesLabel,
                  data: report.byType.map((x) => x.minutes),
                },
              ]}
            />
          </ChartCard>
          <ChartCard title={t.time.byDayChart}>
            <LineChart
              height={280}
              xAxis={[
                {
                  scaleType: "time",
                  data: report.byDay.map((d) => new Date(d.date)),
                },
              ]}
              series={[
                {
                  label: t.time.minutesLabel,
                  data: report.byDay.map((d) => d.minutes),
                  showMark: false,
                },
              ]}
            />
          </ChartCard>
        </Box>
      )}
    </PageShell>
  );
}

function ChartCard({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <Card variant="outlined">
      <CardHeader
        title={<Typography variant="subtitle2">{title}</Typography>}
      />
      <CardContent sx={{ pt: 0 }}>{children}</CardContent>
    </Card>
  );
}
