// spec 0097 S2:sprint = 组(type='sprint');本 helper 把统一组端点映射成 sprint 语义,页面零散调用收敛于此。
import { api } from "./api";

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
  const groups = await api<GroupDto[]>(
    `/api/projects/${key}/groups?type=sprint`,
  );
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
      body: JSON.stringify({ typeKey: "sprint", ...body }),
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
  await api(`/api/items/${itemId}/group-membership`, {
    method: "PUT",
    body: JSON.stringify({ groupId: sprintId, groupType: "sprint" }),
  });
}
