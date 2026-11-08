// spec 0100 切片C:sprint = level-1 组(不再按 typeKey 寻型);本 helper 把统一组端点映射成 sprint 语义,页面零散调用收敛于此。
import { api } from "./api";
import { t } from "./texts";

export type Sprint = {
  sprintId: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
  /** spec 0111:父层组公开 ID;顶层/无父恒 null(spec 0114 多层泛化)。 */
  parentGroupId: string | null;
  /** spec 0114:组类型 key——详情页按自身层位找 L+1 候选/workflow。 */
  typeKey: string;
};

/** spec 0112:树节点(非叶层组带 isParent 标记)。 */
export type TreeSprint = Sprint & { isParent: boolean };

/** spec 0114:递归树节点(多层缩进渲染)。 */
export type GroupNode = { sprint: TreeSprint; children: GroupNode[] };

export type GroupTree = {
  /** 顶层组(无父的非叶层组)及其子链;每层按 active→planned→completed 排序。 */
  nodes: GroupNode[];
  /** 悬空叶层组(无父的 level-1 组),平面区渲染。 */
  orphans: TreeSprint[];
  /** level-2 组全集(叶层新建挂父候选;两层项目=原 parents)。 */
  parentCandidates: TreeSprint[];
  /** level-2 类型名(「所属X」标签);无 level-2 类型为 null(=平面模式)。 */
  parentTypeName: string | null;
  /** level-1 组全集(叶层;syncCurrentParent 判定用)。 */
  leafSprints: TreeSprint[];
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
    typeKey: g.typeKey,
  };
}

export async function listSprints(key: string): Promise<Sprint[]> {
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups?level=1`);
  return groups.map(toSprint);
}

/** spec 0114:多层树数据——一次全量组,按 parentGroupId 组装递归结构;无 level-2 → parentCandidates 空(=平面模式)。 */
export async function listGroupTree(key: string): Promise<GroupTree> {
  const types = await api<GroupTypeDto[]>(`/api/projects/${key}/group-types`);
  const l1 = types.find((tp) => tp.level === 1)?.typeKey;
  const l2 = types.find((tp) => tp.level === 2);
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups`);
  const toTree = (g: GroupDto): TreeSprint => ({
    ...toSprint(g),
    isParent: g.typeKey !== l1,
  });
  const rank = { active: 0, planned: 1, completed: 2 } as const;
  const byRank = (a: TreeSprint, b: TreeSprint) =>
    (rank[a.status as keyof typeof rank] ?? 9) -
    (rank[b.status as keyof typeof rank] ?? 9);
  const nodeOf = new Map<string, GroupNode>(
    groups.map((g) => [g.groupId, { sprint: toTree(g), children: [] }]),
  );
  const nodes: GroupNode[] = [];
  const orphans: TreeSprint[] = [];
  for (const g of groups) {
    const n = nodeOf.get(g.groupId)!;
    const p = g.parentGroupId ? nodeOf.get(g.parentGroupId) : null;
    if (p) p.children.push(n);
    else if (g.typeKey === l1) orphans.push(n.sprint);
    else nodes.push(n);
  }
  const sortNodes = (ns: GroupNode[]) => {
    ns.sort((a, b) => byRank(a.sprint, b.sprint));
    ns.forEach((n) => sortNodes(n.children));
  };
  sortNodes(nodes);
  orphans.sort(byRank);
  const parentCandidates = groups
    .filter((g) => g.typeKey === l2?.typeKey)
    .map(toTree)
    .sort(byRank);
  return {
    nodes,
    orphans,
    parentCandidates,
    parentTypeName: l2?.name ?? null,
    leafSprints: groups.filter((g) => g.typeKey === l1).map(toTree),
  };
}

/** spec 0114:active 叶层组的父链节点 id(侧栏默认展开);无 active 叶层返回空。 */
export function activeChainIds(tree: GroupTree): string[] {
  const leafIds = new Set(tree.leafSprints.map((s) => s.sprintId));
  const walk = (n: GroupNode, path: string[]): string[] | null => {
    if (leafIds.has(n.sprint.sprintId))
      return n.sprint.status === "active" ? path : null;
    for (const c of n.children) {
      const hit = walk(c, [...path, n.sprint.sprintId]);
      if (hit) return hit;
    }
    return null;
  };
  for (const r of tree.nodes) {
    const hit = walk(r, []);
    if (hit) return hit;
  }
  return [];
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

/** spec 0114:某组类型 L 的上一层组候选(L+1 类型组列表);无上层类型返回 null(顶层/平面项目,不渲染选择器)。 */
export async function listParentCandidates(
  key: string,
  groupTypeKey: string,
): Promise<{ name: string; options: TreeSprint[] } | null> {
  const types = await api<GroupTypeDto[]>(`/api/projects/${key}/group-types`);
  const self = types.find((tp) => tp.typeKey === groupTypeKey);
  const up =
    self?.level != null
      ? types.find((tp) => tp.level === self.level! + 1)
      : undefined;
  if (!up) return null;
  const groups = await api<GroupDto[]>(`/api/projects/${key}/groups`);
  const options = groups
    .filter((g) => g.typeKey === up.typeKey)
    .map((g) => ({ ...toSprint(g), isParent: true }));
  options.sort(byRank);
  return { name: up.name, options };
}
const rank3 = { active: 0, planned: 1, completed: 2 } as const;
const byRank = (a: TreeSprint, b: TreeSprint) =>
  (rank3[a.status as keyof typeof rank3] ?? 9) -
  (rank3[b.status as keyof typeof rank3] ?? 9);

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
  // YPJ-120/0114:自定义工作流出边 function 可能是 switch 等,按出边实际名传
  fn: string,
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
