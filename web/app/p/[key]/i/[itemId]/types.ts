import { type Signal, type Verdict } from "@/components/Verdict";

export type Status = {
  statusId: string;
  name: string;
  statusType: string;
  position: number;
};

/** YPJ-177(spec 0123):item type 定义(动态下拉)。 */
export type ItemTypeLite = {
  typeKey: string;
  name: string;
  builtin: boolean;
};

export type Item = {
  itemId: string;

  number: string;

  title: string;

  description: string | null;

  type: string;

  status: string;

  assignee: string | null;

  parentItemId: string | null;

  parentNumber?: string | null;

  externalRef: string | null;

  requirements: { attribute: string; minLevel: number | null }[];

  attributes: { attribute: string; value: string }[] | null;

  startDate: string | null;

  dueDate: string | null;

  gitRefs: {
    kind: string;
    repo: string;
    ref: string;
    url: string | null;
    state: string | null;
  }[];

  priority: string | null;

  estimateValue: number | null; // YPJ-186 预估快照

  estimateUnit: string | null;

  sprintId: string | null;

  sprintName: string | null;

  /** 票 2(US-M7):gate 多归属回显。 */

  milestoneIds: string[];

  overdue: boolean;

  dueSoon: boolean;

  blocked: boolean;

  createdAt: string | null;
};

export type SprintLite = { sprintId: string; name: string; status: string };

export type MilestoneLite = {
  milestoneId: string;
  name: string;
  status: string;
};

export type Repo = { repoId: string; repo: string };

export type Attribute = {
  attributeId: string;
  name: string;
  kind: string;
  leveled: boolean;
};

export type Feasibility = {
  itemId: string;

  number: string;

  title: string;

  signal: Signal;

  missingCount: number;

  totalDelta: number;

  verdicts: Verdict[];
};

export type CommitBlock = {
  repo: string;

  ref: string;

  viaPr?: boolean;

  prNumber?: number;

  commits: {
    sha: string;
    message: string;
    author: string | null;
    date: string | null;
    url: string | null;
  }[];
};

export type Activity = {
  objectId: string;

  kind: string;

  oldValue: string | null;

  newValue: string | null;

  actorMemberId: number | null;

  createdAt: string;
};

export type ReqRow = { attribute: string; minLevel: number | null };

export type AttrRow = { attribute: string; value: string };

export type CommentDto = {
  commentId: string;
  body: string;
  author: string;
  createdAt: string;
};

export type Dep = {
  dependencyItemId: string;
  itemId: string;
  number: string;
  title: string;
  statusName: string;
  final: boolean;
};

export type ChecklistEntry = {
  checklistItemId: string;
  text: string;
  done: boolean;
};

export type Checklist = {
  entries: ChecklistEntry[];
  doneCount: number;
  totalCount: number;
};

export type TimeEntry = {
  timeEntryId: string;

  member: string;

  startedAt: string;

  endedAt: string | null;

  minutes: number;

  note: string | null;
};

export type SuggestedEntry = {
  repo: string;

  ref: string;

  commitCount: number;

  startedAt: string;

  endedAt: string;

  note: string;
};

export const activityLabel: Record<string, string> = {
  created: "创建",

  status_changed: "状态变更",

  title_changed: "标题变更",

  description_changed: "描述变更",

  assigned: "指派",

  unassigned: "取消指派",

  requirement_changed: "需求变更",

  github_status_changed: "GitHub 流转",

  github_branch_created: "GitHub 分支",

  dates_changed: "日期变更",

  attribute_changed: "属性变更",
};

// 编号直达(YPJ-63):uuid 直通;编号经现有 /api/search 精确解析,地址栏保持编号

export const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type SearchHit = { itemId: string; number: string; projectKey: string };
