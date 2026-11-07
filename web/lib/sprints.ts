// spec 0100 切片C:sprint = level-1 组(不再按 typeKey 寻型);本 helper 把统一组端点映射成 sprint 语义,页面零散调用收敛于此。
import { api } from "./api";
import { t } from "./texts";

export type Sprint = {
  sprintId: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
  /** spec 0111:父层组公开 ID;叶层组可带,父层/无父恒 null。 */
  parentGroupId: string | null;
};

/** spec 0112:两级树节点(父层组带 isParent 标记)。 */
export type TreeSprint = Sprint & { isParent: boolean };

export type GroupTree = {
  parents: TreeSprint[];
  orphans: TreeSprint[];
  childrenByParent: Record<string, TreeSprint[]>;
  parentTypeName: string | null;
};

type GroupDto = {
  groupId: string;
  typeKey: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
  parentGroupId: string | null;
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
    parentGroupId: g.parentGroupId,
  };
}

export async function listSprints(key: string): Promise<Sprint[]> {
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups?level=1`);
  return groups.map(toSprint);
}

/** spec 0112:两级树数据——一次全量组,前端分桶(父层/子组/孤儿);无 level-2 → parents 空(=平面模式)。 */
export async function listGroupTree(key: string): Promise<GroupTree> {
  const types = await api<GroupTypeDto[]>(`/api/projects/${key}/group-types`);
  const l1 = types.find((tp) => tp.level === 1)?.typeKey;
  const l2 = types.find((tp) => tp.level === 2);
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups`);
  const toTree = (g: GroupDto): TreeSprint => ({
    ...toSprint(g),
    isParent: g.typeKey === l2?.typeKey,
  });
  const rank = { active: 0, planned: 1, completed: 2 } as const;
  const byRank = (a: TreeSprint, b: TreeSprint) =>
    (rank[a.status as keyof typeof rank] ?? 9) -
    (rank[b.status as keyof typeof rank] ?? 9);
  const parents = groups
    .filter((g) => g.typeKey === l2?.typeKey)
    .map(toTree)
    .sort(byRank);
  const childrenByParent: Record<string, TreeSprint[]> = {};
  groups
    .filter((g) => g.typeKey === l1 && g.parentGroupId)
    .map(toTree)
    .forEach((c) => {
      (childrenByParent[c.parentGroupId as string] ??= []).push(c);
    });
  Object.values(childrenByParent).forEach((arr) => arr.sort(byRank));
  const orphans = groups
    .filter((g) => g.typeKey === l1 && !g.parentGroupId)
    .map(toTree);
  return {
    parents,
    orphans,
    childrenByParent,
    parentTypeName: l2?.name ?? null,
  };
}

/** spec 0112:当前 epic 判定——active 子组的父 → localStorage 记忆 → null;发现即回写记忆。 */
export function syncCurrentParent(
  key: string,
  sprints: { status: string; parentGroupId: string | null }[],
): string | null {
  const cur =
    sprints.find((s) => s.status === "active" && s.parentGroupId)
      ?.parentGroupId ?? null;
  if (!cur && typeof window !== "undefined") {
    try {
      return window.localStorage.getItem(`yz-current-parent:${key}`);
    } catch {
      /* 忽略 */
    }
  }
  if (cur && typeof window !== "undefined") {
    try {
      window.localStorage.setItem(`yz-current-parent:${key}`, cur);
    } catch {
      /* 忽略 */
    }
  }
  return cur;
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
  body: {
    name: string;
    startDate?: string | null;
    endDate?: string | null;
    /** spec 0112:挂父层组(当前 epic 默认);空 = 无父,向后兼容。 */
    parentGroupId?: string | null;
  },
): Promise<Sprint> {
  const { parentGroupId, ...rest } = body;
  return toSprint(
    await api<GroupDto>(`/api/projects/${key}/groups`, {
      method: "POST",
      body: JSON.stringify({
        typeKey: await level1TypeKey(key),
        ...rest,
        parentGroupId: parentGroupId || null,
      }),
    }),
  );
}

/** 状态变更走动作白名单:active=start / completed=complete / planned=backward。 */
export async function sprintAction(
  key: string,
  sprintId: string,
  fn: "start" | "complete" | "backward" | "reopen",
  body?: Record<string, unknown>, // 0110:complete 可携处置计划,与状态迁移同事务
): Promise<Sprint> {
  const s = toSprint(
    await api<GroupDto>(
      `/api/projects/${key}/groups/${sprintId}/actions/${fn}`,
      { method: "POST", body: JSON.stringify(body ?? {}) },
    ),
  );
  // YPJ-74/0105:组状态变化广播,sidebar 即时重拉(sprint 详情页手工派发收口至此)
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event("sprints-changed"));
  }
  return s;
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

/** spec 0113(YPJ-160):组改父——PATCH groups 扩 parentGroupId;置空由后端拒绝(两级项目不变量)。 */
export async function updateGroupParent(
  key: string,
  groupId: string,
  parentGroupId: string,
): Promise<void> {
  await api(`/api/projects/${key}/groups/${groupId}`, {
    method: "PATCH",
    body: JSON.stringify({ parentGroupId }),
  });
}

/** spec 0113:父层组(level-2 类型)列表——详情页「所属 Epic」选项;无 level-2 类型返回 null(=平面项目,不渲染选择器)。 */
export async function listParents(key: string): Promise<TreeSprint[] | null> {
  const types = await api<GroupTypeDto[]>(`/api/projects/${key}/group-types`);
  const l2 = types.find((tp) => tp.level === 2);
  if (!l2) return null;
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups`);
  return groups
    .filter((g) => g.typeKey === l2.typeKey)
    .map((g) => ({ ...toSprint(g), isParent: true }));
}
