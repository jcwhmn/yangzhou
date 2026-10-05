"use client";

import { Alert, Box, Card, CardContent, Chip, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { signalColor, type Signal } from "@/components/Verdict";

type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  feasSignal: Signal | null;
  overdue: boolean;
  blocked: boolean;
};
type Sprint = { sprintId: string; name: string; status: string };
type Member = { memberId: string; displayName: string; virtual: boolean };
type SprintBrief = {
  sprintId: string;
  name: string;
  done: number;
  total: number;
  byMember: { name: string; done: number; total: number }[];
};

/**
 * V13-S1 项目 Overview:纯前端聚合现有 API(零新表零新端点),五区块——
 * 状态分布 / 可行性信号 / 我的 / 风险 / Sprint 概况。当前用户 = 唯一非虚拟成员。
 */
export default function OverviewPage() {
  const { key } = useParams<{ key: string }>();
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [me, setMe] = useState<string | null>(null);
  const [sprint, setSprint] = useState<SprintBrief | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [proj, its, sps, mems] = await Promise.all([
        api<{ statuses: Status[] }>(`/api/projects/${key}`),
        api<Item[]>(`/api/projects/${key}/items`),
        api<Sprint[]>(`/api/projects/${key}/sprints`),
        api<Member[]>(`/api/members`),
      ]);
      const finalByName = new Map(proj.statuses.map((s) => [s.name, s.isFinal]));
      setStatuses(proj.statuses);
      setItems(its);
      setMe(mems.find((m) => !m.virtual)?.displayName ?? null);
      const active = sps.find((s) => s.status === "active");
      if (!active) {
        setSprint(null);
        return;
      }
      const sItems = await api<Item[]>(`/api/projects/${key}/sprints/${active.sprintId}/items`);
      const byMember = new Map<string, { done: number; total: number }>();
      let done = 0;
      for (const it of sItems) {
        const fin = finalByName.get(it.status) === true;
        if (fin) done++;
        const name = it.assignee ?? t.overview.unassigned;
        const e = byMember.get(name) ?? { done: 0, total: 0 };
        e.total++;
        if (fin) e.done++;
        byMember.set(name, e);
      }
      setSprint({
        sprintId: active.sprintId,
        name: active.name,
        done,
        total: sItems.length,
        byMember: [...byMember].map(([name, v]) => ({ name, ...v })),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  const isFinal = (s: string) => statuses.find((st) => st.name === s)?.isFinal === true;
  const mine = items.filter((i) => i.assignee !== null && i.assignee === me && !isFinal(i.status));
  const risks = items.filter((i) => i.overdue || i.blocked);
  const sig = { GREEN: 0, YELLOW: 0, RED: 0 } as Record<Signal, number>;
  for (const i of items) if (i.feasSignal) sig[i.feasSignal]++;

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" sx={{ mb: 2 }}>
        {t.overview.title}
      </Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2 }}>
        <Card>
          <CardContent>
            <Typography color="text.secondary" variant="subtitle2" gutterBottom>
              {t.overview.statusDist}
            </Typography>
            {statuses.map((s) => (
              <Typography key={s.statusId} variant="body2" sx={{ py: 0.5 }}>
                {s.name}
                {s.isFinal ? t.overview.finalTag : ""} · {items.filter((i) => i.status === s.name).length}
              </Typography>
            ))}
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <Typography color="text.secondary" variant="subtitle2" gutterBottom>
              {t.overview.signalDist}
            </Typography>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              {(Object.keys(sig) as Signal[]).map((s) => (
                <Chip key={s} label={`${t.signal[s]} · ${sig[s]}`} color={signalColor[s]} variant="outlined" size="small" />
              ))}
            </Stack>
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <Typography color="text.secondary" variant="subtitle2" gutterBottom>
              {t.overview.mine}
            </Typography>
            {mine.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t.overview.noMine}</Typography>
            ) : (
              mine.map((i) => <ItemRow key={i.itemId} href={`/p/${key}/i/${i.itemId}`} item={i} />)
            )}
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <Typography color="text.secondary" variant="subtitle2" gutterBottom>
              {t.overview.risk}
            </Typography>
            {risks.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t.overview.noRisk}</Typography>
            ) : (
              risks.map((i) => (
                <ItemRow
                  key={i.itemId}
                  href={`/p/${key}/i/${i.itemId}`}
                  item={i}
                  trailing={
                    <>
                      {i.overdue && <Chip label={t.overview.overdue} size="small" color="error" />}
                      {i.blocked && <Chip label={t.overview.blocked} size="small" color="warning" />}
                    </>
                  }
                />
              ))
            )}
          </CardContent>
        </Card>
        <Card sx={{ gridColumn: { md: "1 / -1" } }}>
          <CardContent>
            <Typography color="text.secondary" variant="subtitle2" gutterBottom>
              {t.overview.sprint}
            </Typography>
            {!sprint ? (
              <Typography variant="body2" color="text.secondary">{t.overview.noSprint}</Typography>
            ) : (
              <>
                <Box sx={{ display: "flex", justifyContent: "space-between", py: 0.5 }}>
                  <Typography
                    component={Link}
                    href={`/p/${key}/sprint/${sprint.sprintId}`}
                    variant="body2"
                    sx={{ color: "primary.main", textDecoration: "none" }}
                  >
                    {sprint.name}
                  </Typography>
                  <Typography variant="body2">
                    {sprint.done}/{sprint.total} {t.overview.done}
                  </Typography>
                </Box>
                {sprint.byMember.map((m) => (
                  <Typography key={m.name} variant="body2" color="text.secondary" sx={{ py: 0.25 }}>
                    {m.name} · {m.done}/{m.total}
                  </Typography>
                ))}
              </>
            )}
          </CardContent>
        </Card>
      </Box>
    </Box>
  );
}

function ItemRow({
  href,
  item,
  trailing,
}: {
  href: string;
  item: Item;
  trailing?: React.ReactNode;
}) {
  return (
    <Box
      component={Link}
      href={href}
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 1,
        py: 0.5,
        color: "text.primary",
        textDecoration: "none",
        "&:hover": { bgcolor: "grey.100" },
      }}
    >
      <Typography variant="body2" sx={{ flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {item.number} {item.title}
      </Typography>
      {trailing}
    </Box>
  );
}
