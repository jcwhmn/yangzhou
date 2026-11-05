// spec 0100 切片C:sprint = level-1 组(不再按 typeKey 寻型);本 helper 把统一组端点映射成 sprint 语义,页面零散调用收敛于此。
import { api } from "./api";
import { t } from "./texts";

export type Sprint = {
  sprintId: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
};

type GroupDto = {
  groupId: string;
  typeKey: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
};

type GroupTypeDto = { typeKey: string; name: string; level: number | null };

/** level-1 类型(typeKey+显示名);未配置返回 null(= 项目无组概念,S0)。 */
export async function level1Type(
  key: string,
): Promise<{ typeKey: string; name: string } | null> {
  const types = await api<GroupTypeDto[]>(`/api/projects/${key}/group-types`);
  const l1 = types.find((tp) => tp.level === 1);
  return l1 ? { typeKey: l1.typeKey, name: l1.name } : null;
}

/** level-1 类型 key(本项目唯一口径);项目未配置 level-1 时报错引导。 */
export async function level1TypeKey(key: string): Promise<string> {
  const l1 = await level1Type(key);
  if (!l1) throw new Error(t.sprints.noLevel1);
  return l1.typeKey;
}

function toSprint(g: GroupDto): Sprint {
  return {
    sprintId: g.groupId,
    name: g.name,
    status: g.status,
    startDate: g.startDate,
    endDate: g.endDate,
  };
}

export async function listSprints(key: string): Promise<Sprint[]> {
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups?level=1`);
  return groups.map(toSprint);
}

export async function getSprint(
  key: string,
  sprintId: string,
): Promise<Sprint> {
  return toSprint(
    await api<GroupDto>(`/api/projects/${key}/groups/${sprintId}`),
  );
}

export async function createSprint(
  key: string,
  body: { name: string; startDate?: string | null; endDate?: string | null },
): Promise<Sprint> {
  return toSprint(
    await api<GroupDto>(`/api/projects/${key}/groups`, {
      method: "POST",
      body: JSON.stringify({ typeKey: await level1TypeKey(key), ...body }),
    }),
  );
}

/** 状态变更走动作白名单:active=start / completed=complete / planned=backward。 */
export async function sprintAction(
  key: string,
  sprintId: string,
  fn: "start" | "complete" | "backward",
): Promise<Sprint> {
  return toSprint(
    await api<GroupDto>(
      `/api/projects/${key}/groups/${sprintId}/actions/${fn}`,
      { method: "POST", body: JSON.stringify({}) },
    ),
  );
}

/** 指派/移出 sprint(groupId=null = 移出);按类型替换归属,release 正交不受影响。 */
export async function assignSprint(
  key: string,
  itemId: string,
  sprintId: string | null,
): Promise<void> {
  const body =
    sprintId === null
      ? { groupId: null, groupType: await level1TypeKey(key) }
      : { groupId: sprintId };
  await api(`/api/items/${itemId}/group-membership`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}
