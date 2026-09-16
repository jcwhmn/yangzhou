"use client";

import { Box, Card, CardContent, Chip, Container, MenuItem, Select, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Member = { memberId: string; displayName: string; virtual: boolean };
type StandupItem = {
  itemId: string;
  number: string;
  projectKey: string;
  title: string;
  statusName: string;
};
type Standup = {
  memberId: string;
  displayName: string;
  doneYesterday: StandupItem[];
  today: StandupItem[];
  blocked: StandupItem[];
};

/** V9-S4 每日站会:按人三组(昨日完成/今日名下/阻塞中),范围 = workspace 全项目。 */
export default function StandupPage() {
  const [members, setMembers] = useState<Member[]>([]);
  const [memberId, setMemberId] = useState<string>("");
  const [data, setData] = useState<Standup | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      setData(await api<Standup>(`/api/standup${memberId ? `?member=${memberId}` : ""}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [memberId]);

  useEffect(() => {
    api<Member[]>("/api/members")
      .then((ms) => {
        setMembers(ms);
        const first = ms.find((m) => !m.virtual) ?? ms[0];
        if (first) setMemberId(first.memberId);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, []);

  useEffect(() => {
    if (memberId) load();
  }, [memberId, load]);

  function group(title: string, color: "success" | "primary" | "error", list: StandupItem[] | undefined) {
    return (
      <Box sx={{ mb: 3 }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">{title}</Typography>
          <Chip size="small" label={list?.length ?? 0} color={color} />
        </Stack>
        {(!list || list.length === 0) && <Typography color="text.secondary">(无)</Typography>}
        <Stack spacing={1}>
          {(list ?? []).map((it) => (
            <Card key={`${it.projectKey}-${it.itemId}`} variant="outlined">
              <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip size="small" variant="outlined" label={it.number} />
                  <Link href={`/p/${it.projectKey}/i/${it.itemId}`}>
                    <Typography variant="body2">{it.title}</Typography>
                  </Link>
                  <Chip size="small" label={it.statusName} sx={{ ml: "auto" }} />
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      </Box>
    );
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">站会</Typography>
        <Select size="small" value={memberId} onChange={(e) => setMemberId(String(e.target.value))} sx={{ minWidth: 160 }}>
          {members.map((m) => (
            <MenuItem key={m.memberId} value={m.memberId}>
              {m.displayName}
              {m.virtual ? "(虚拟)" : ""}
            </MenuItem>
          ))}
        </Select>
      </Stack>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {data && (
        <>
          {group("✅ 昨日完成", "success", data.doneYesterday)}
          {group("📋 今日名下", "primary", data.today)}
          {group("⛔ 阻塞中", "error", data.blocked)}
        </>
      )}
    </Container>
  );
}
