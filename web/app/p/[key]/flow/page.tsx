"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import {
  Box,
  Card,
  CardContent,
  CardHeader,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { BarChart } from "@mui/x-charts/BarChart";
import { LineChart } from "@mui/x-charts/LineChart";
import { ChartsReferenceLine } from "@mui/x-charts/ChartsReferenceLine";
import { ScatterChart } from "@mui/x-charts/ScatterChart";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

// YPJ-46 项目级 Flow 页(spec 0044):五图一表 + velocity/burnup,数据全来自只读 metrics 端点。
type AssigneeCount = { memberObjectId: string | null; displayName: string | null; count: number; byStatus: Record<string, number> };
type Workload = { byStatus: { name: string; isStart: boolean; isFinal: boolean; count: number }[]; byAssignee: AssigneeCount[] };
type Throughput = { buckets: { start: string; completed: number }[] };
type FlowTimes = {
  items: { itemId: string; number: number; title: string; completedAt: string; cycleDays: number | null; leadDays: number }[];
  percentiles: { cycleP50: number | null; cycleP85: number | null; cycleP95: number | null; leadP50: number | null; leadP85: number | null; leadP95: number | null };
};
type Cfd = { statuses: { statusId: string; name: string; isFinal: boolean }[]; series: { date: string; counts: Record<string, number> }[] };
type Aging = { aging: { number: number; title: string; statusName: string; enteredAt: string; ageDays: number }[]; queueWidth: { name: string; count: number }[] };
type Velocity = { sprints: { name: string; endDate: string; completed: number }[] };
type Burnup = { done: { date: string; cumulative: number }[]; scope: { date: string; total: number }[] };
type Sprint = { sprintId: string; name: string; status: string; startDate: string | null; endDate: string | null };

function Card2({ title, caption, children }: { title: string; caption?: string; children: React.ReactNode }) {
  return (
    <Card variant="outlined">
      <CardHeader title={<Typography variant="subtitle2">{title}</Typography>} />
      <CardContent>{children}</CardContent>
      {caption && (
        <Typography variant="caption" color="text.secondary" sx={{ px: 2, pb: 1, display: "block" }}>
          {caption}
        </Typography>
      )}
    </Card>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <Typography variant="body2" color="text.secondary" sx={{ py: 6, textAlign: "center" }}>
      {text}
    </Typography>
  );
}

export default function FlowPage() {
  const { key } = useParams<{ key: string }>();
  const [data, setData] = useState<{
    workload?: Workload;
    throughput?: Throughput;
    flowTimes?: FlowTimes;
    cfd?: Cfd;
    aging?: Aging;
    velocity?: Velocity;
    burnup?: Burnup;
  }>({});
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    const from = new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10);
    const to = new Date().toISOString().slice(0, 10);
    (async () => {
      const [workload, throughput, flowTimes, cfd, aging, velocity, sprints] = await Promise.all([
        api<Workload>(`/api/projects/${key}/metrics/workload`),
        api<Throughput>(`/api/projects/${key}/metrics/throughput?from=${from}&to=${to}&bucket=day`),
        api<FlowTimes>(`/api/projects/${key}/metrics/flow-times?from=${from}&to=${to}`),
        api<Cfd>(`/api/projects/${key}/metrics/cfd?from=${from}&to=${to}`),
        api<Aging>(`/api/projects/${key}/metrics/aging`),
        api<Velocity>(`/api/projects/${key}/metrics/velocity`),
        api<Sprint[]>(`/api/projects/${key}/sprints`),
      ]);
      const active = (sprints ?? []).find((s) => s.status === "active");
      const burnup = active && active.startDate && active.endDate
        ? await api<Burnup>(`/api/projects/${key}/metrics/burnup?groupId=${active.sprintId}`)
        : undefined;
      if (alive) {
        setData({ workload, throughput, flowTimes, cfd, aging, velocity, burnup });
        setLoaded(true);
      }
    })().catch(() => alive && setLoaded(true));
    return () => {
      alive = false;
    };
  }, [key]);

  const wipSeries = useMemo(() => {
    const statuses = (data.workload?.byStatus ?? []).filter((s) => !s.isStart && !s.isFinal).map((s) => s.name);
    const rows = data.workload?.byAssignee ?? [];
    return {
      xAxis: [{ scaleType: "band" as const, data: rows.map((a) => a.displayName ?? t.overview.unassigned) }],
      series: statuses.map((st) => ({
        label: st,
        data: rows.map((a) => a.byStatus[st] ?? 0),
        stack: "wip",
      })),
    };
  }, [data.workload]);

  const scatterData = useMemo(() => {
    const items = data.flowTimes?.items ?? [];
    const pt = (cycle: boolean) =>
      items
        .filter((i) => (cycle ? i.cycleDays != null : true))
        .map((i) => ({ x: new Date(i.completedAt).getTime(), y: cycle ? (i.cycleDays as number) : i.leadDays, id: `${i.itemId}-${cycle}` }));
    return [
      { label: t.flow.cycle, data: pt(true) },
      { label: t.flow.lead, data: pt(false) },
    ];
  }, [data.flowTimes]);

  const cfdSeries = useMemo(() => {
    const statuses = data.cfd?.statuses ?? [];
    return statuses.map((s) => ({
      label: s.name,
      data: (data.cfd?.series ?? []).map((p) => p.counts[s.statusId] ?? 0),
      stack: "cfd",
    }));
  }, [data.cfd]);

  const burnupSeries = useMemo(
    () => ({
      xAxis: [{ scaleType: "time" as const, data: (data.burnup?.scope ?? []).map((p) => new Date(p.date)) }],
      series: [
        { label: t.flow.scope, data: (data.burnup?.scope ?? []).map((p) => p.total), showMark: false },
        { label: t.flow.done, data: (data.burnup?.done ?? []).map((p) => p.cumulative), showMark: false },
      ],
    }),
    [data.burnup],
  );

  const velocityRows = useMemo(
    () =>
      [...(data.velocity?.sprints ?? [])]
        .filter((s) => s.endDate)
        .sort((a, b) => a.endDate.localeCompare(b.endDate))
        .slice(-8),
    [data.velocity],
  );

  if (!loaded) return <Typography sx={{ p: 3 }}>…</Typography>;

  return (
    <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2 }}>
      <Card2 title={t.flow.wip}>
        {(data.workload?.byAssignee ?? []).length === 0 ? (
          <Empty text={t.flow.empty} />
        ) : (
          <BarChart height={280} {...wipSeries}  />
        )}
      </Card2>
      <Card2 title={t.flow.throughput}>
        {(data.throughput?.buckets ?? []).length === 0 ? (
          <Empty text={t.flow.empty} />
        ) : (
          <BarChart
            height={280}
            xAxis={[{ scaleType: "band", data: (data.throughput!.buckets ?? []).map((b) => b.start.slice(5)) }]}
            series={[{ label: t.flow.throughput, data: data.throughput!.buckets.map((b) => b.completed) }]}
          />
        )}
      </Card2>
      <Card2 title={t.flow.scatter} caption={`p50/p85: cycle=${data.flowTimes?.percentiles.cycleP50 ?? "-"} / ${data.flowTimes?.percentiles.cycleP85 ?? "-"}, lead p95=${data.flowTimes?.percentiles.leadP95 ?? "-"}`}>
        {scatterData.every((s) => s.data.length === 0) ? (
          <Empty text={t.flow.empty} />
        ) : (
          <ScatterChart
            height={280}
            series={scatterData}
            xAxis={[{ label: t.flow.done, scaleType: "time", min: Date.now() - 31 * 864e5, max: Date.now() }]}
            yAxis={[{ label: "days" }]}
            grid={{ vertical: true, horizontal: true }}
          >
            <ChartsReferenceLine y={data.flowTimes?.percentiles.cycleP50 ?? 0} label="cycle p50" />
            <ChartsReferenceLine y={data.flowTimes?.percentiles.cycleP85 ?? 0} label="cycle p85" />
          </ScatterChart>
        )}
      </Card2>
      <Card2 title={t.flow.cfd}>
        {(data.cfd?.series ?? []).length === 0 ? (
          <Empty text={t.flow.empty} />
        ) : (
          <BarChart
            height={280}
            xAxis={[{ scaleType: "band", data: (data.cfd!.series ?? []).map((p) => p.date.slice(5)) }]}
            series={cfdSeries}
          />
        )}
      </Card2>
      <Card2 title={t.flow.aging} caption={(data.aging?.queueWidth ?? []).map((q) => `${q.name}:${q.count}`).join("  ·  ")}>
        {(data.aging?.aging ?? []).length === 0 ? (
          <Empty text={t.flow.empty} />
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 280 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>{t.flow.item}</TableCell>
                  <TableCell>{t.flow.status}</TableCell>
                  <TableCell align="right">{t.flow.ageDays}</TableCell>
                  <TableCell>{t.flow.enteredAt}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.aging!.aging.map((a) => (
                  <TableRow key={a.number}>
                    <TableCell>{`#${a.number} ${a.title}`}</TableCell>
                    <TableCell>{a.statusName}</TableCell>
                    <TableCell align="right">{Math.round(a.ageDays)}</TableCell>
                    <TableCell>{a.enteredAt.slice(0, 10)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card2>
      <Card2 title={t.flow.velocity}>
        {velocityRows.length === 0 ? (
          <Empty text={t.flow.empty} />
        ) : (
          <BarChart
            height={280}
            xAxis={[{ scaleType: "band", data: velocityRows.map((s) => s.name) }]}
            series={[{ label: t.flow.velocity, data: velocityRows.map((s) => s.completed) }]}
          />
        )}
      </Card2>
      <Box>
        <Card2 title={t.flow.burnup}>
          {!data.burnup ? (
            <Empty text={t.flow.noActiveSprint} />
          ) : (
            <LineChart height={280} {...burnupSeries} />
          )}
        </Card2>
      </Box>
    </Box>
  );
}
