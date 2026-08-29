"use client";

import {
  Button,
  Card,
  CardContent,
  Chip,
  Container,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Member = { memberId: string; displayName: string; username: string | null; virtual: boolean };
type Team = { teamId: string; name: string; members: { memberId: string; displayName: string }[] };
type Attribute = { attributeId: string; name: string; kind: string; leveled: boolean };
type Capability = { attribute: string; level: number | null };

/** 成员管理页:虚拟成员增删 + 逐成员能力自评 + Team 分组(Q2 dogfood:UI 面向"我管理一群人")。 */
export default function MembersPage() {
  const [members, setMembers] = useState<Member[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [attributes, setAttributes] = useState<Attribute[]>([]);
  const [caps, setCaps] = useState<Map<string, Map<string, number | null>>>(new Map()); // memberId → (attrName → level)
  const [newName, setNewName] = useState("");
  const [teamName, setTeamName] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const [ms, ts, attrs] = await Promise.all([
      api<Member[]>("/api/members"),
      api<Team[]>("/api/teams"),
      api<Attribute[]>("/api/attributes"),
    ]);
    setMembers(ms);
    setTeams(ts);
    setAttributes(attrs);
    // 逐成员拉能力(虚拟成员页是管理页,数量个位数,可接受)
    const map = new Map<string, Map<string, number | null>>();
    await Promise.all(
      ms.map(async (m) => {
        const caps = await api<Capability[]>(`/api/members/${m.memberId}/capabilities`);
        map.set(m.memberId, new Map(caps.map((c) => [c.attribute, c.level])));
      }),
    );
    setCaps(map);
  }, []);

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, [load]);

  async function addMember(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await api("/api/members", { method: "POST", body: JSON.stringify({ displayName: newName.trim() }) });
      setNewName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  async function deleteMember(m: Member) {
    setError("");
    try {
      await api(`/api/members/${m.memberId}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function setCap(memberId: string, attribute: string, value: string) {
    setError("");
    try {
      if (value === "none") {
        await api(`/api/members/${memberId}/capabilities/${encodeURIComponent(attribute)}`, { method: "DELETE" });
      } else {
        await api(`/api/members/${memberId}/capabilities`, {
          method: "PUT",
          body: JSON.stringify({ attribute, level: value === "unrated" ? null : Number(value) }),
        });
      }
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    }
  }

  async function createTeam(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await api("/api/teams", { method: "POST", body: JSON.stringify({ name: teamName.trim() }) });
      setTeamName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  async function deleteTeam(team: Team) {
    setError("");
    try {
      await api(`/api/teams/${team.teamId}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function teamMemberAction(team: Team, memberId: string, action: "add" | "remove") {
    setError("");
    try {
      if (action === "add") {
        await api(`/api/teams/${team.teamId}/members`, { method: "POST", body: JSON.stringify({ memberId }) });
      } else {
        await api(`/api/teams/${team.teamId}/members/${memberId}`, { method: "DELETE" });
      }
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败");
    }
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        {t.members.title}
      </Typography>
      {error && <Typography color="error" sx={{ mb: 1 }}>{error}</Typography>}

      {/* 建虚拟成员 */}
      <Stack component="form" direction="row" spacing={1} onSubmit={addMember} sx={{ mb: 3 }}>
        <TextField
          size="small"
          label={t.members.newName}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          required
        />
        <Button type="submit" variant="contained">
          {t.members.add}
        </Button>
      </Stack>

      {/* 成员卡片:能力自评 + 删除(登录账号不可删) */}
      <Stack spacing={1.5} sx={{ mb: 4 }}>
        {members.map((m) => (
          <Card key={m.memberId} variant="outlined">
            <CardContent>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                <Typography variant="subtitle1">{m.displayName}</Typography>
                <Chip size="small" label={m.virtual ? t.members.virtual : t.members.real} variant="outlined" />
                {m.virtual && (
                  <IconButton size="small" onClick={() => deleteMember(m)} aria-label="delete">
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                )}
              </Stack>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                {attributes.map((a) => {
                  const has = caps.get(m.memberId)?.has(a.name) ?? false;
                  const level = caps.get(m.memberId)?.get(a.name);
                  const value = !has ? "none" : level === null || level === undefined ? "unrated" : String(level);
                  return (
                    <Stack key={a.attributeId} direction="row" spacing={0.5} alignItems="center">
                      <Typography variant="caption" color="text.secondary">
                        {a.name}
                      </Typography>
                      <Select
                        size="small"
                        value={value}
                        onChange={(e) => setCap(m.memberId, a.name, String(e.target.value))}
                        sx={{ minWidth: 110, fontSize: 13 }}
                      >
                        <MenuItem value="none">{t.caps.none}</MenuItem>
                        <MenuItem value="unrated">{a.leveled ? t.caps.unrated : t.caps.has}</MenuItem>
                        {a.leveled &&
                          [1, 2, 3, 4].map((l) => (
                            <MenuItem key={l} value={String(l)}>
                              {t.caps.level(l)}
                            </MenuItem>
                          ))}
                      </Select>
                    </Stack>
                  );
                })}
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>

      {/* Team 分组 */}
      <Typography variant="h5" gutterBottom>
        {t.members.teams}
      </Typography>
      <Stack component="form" direction="row" spacing={1} onSubmit={createTeam} sx={{ mb: 2 }}>
        <TextField size="small" label={t.members.teamName} value={teamName} onChange={(e) => setTeamName(e.target.value)} required />
        <Button type="submit" variant="outlined">
          {t.members.addTeam}
        </Button>
      </Stack>
      <Stack spacing={1}>
        {teams.map((team) => (
          <Card key={team.teamId} variant="outlined">
            <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
              <Stack direction="row" alignItems="center" justifyContent="space-between">
                <Typography variant="subtitle1">{team.name}</Typography>
                <Button size="small" color="error" onClick={() => deleteTeam(team)}>
                  {t.members.deleteTeam}
                </Button>
              </Stack>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                {team.members.map((m) => (
                  <Chip
                    key={m.memberId}
                    size="small"
                    label={m.displayName}
                    onDelete={() => teamMemberAction(team, m.memberId, "remove")}
                  />
                ))}
                {team.members.length === 0 && (
                  <Typography variant="caption" color="text.secondary">
                    (空)
                  </Typography>
                )}
              </Stack>
              <AddTeamMemberSelect team={team} allMembers={members} onAdd={teamMemberAction} />
            </CardContent>
          </Card>
        ))}
      </Stack>
    </Container>
  );
}

function AddTeamMemberSelect({
  team,
  allMembers,
  onAdd,
}: {
  team: Team;
  allMembers: Member[];
  onAdd: (team: Team, memberId: string, action: "add" | "remove") => void;
}) {
  const [picked, setPicked] = useState("");
  const inTeam = new Set(team.members.map((m) => m.memberId));
  const candidates = allMembers.filter((m) => !inTeam.has(m.memberId));
  if (candidates.length === 0) return null;
  return (
    <Select
      size="small"
      value={picked}
      displayEmpty
      onChange={(e) => {
        const id = String(e.target.value);
        if (id) {
          onAdd(team, id, "add");
          setPicked("");
        }
      }}
      sx={{ minWidth: 160, fontSize: 13, mt: 1 }}
    >
      <MenuItem value="" disabled>
        + 加成员
      </MenuItem>
      {candidates.map((m) => (
        <MenuItem key={m.memberId} value={m.memberId}>
          {m.displayName}
        </MenuItem>
      ))}
    </Select>
  );
}
