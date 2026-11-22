import { type Signal } from "@/components/Verdict";

export type Sprint = {
  sprintId: string;
  name: string;
  status: string;
  startDate: string | null;
  endDate: string | null;
  parentGroupId: string | null;
  typeKey: string;
};
export type Item = {
  itemId: string;
  number: string;
  parentNumber?: string | null;
  title: string;
  status: string;
  assignee: string | null;
  priority: string | null;
  dueDate: string | null;
  feasSignal: Signal | null;
  estimateValue?: number | null; // YPJ-216:候选预估快照(已有预估的票不重录)
  estimateUnit?: string | null;
};
export type Status = {
  statusId: string;
  name: string;
  statusType: string;
  position: number;
};
// spec 0097 S3:类型 workflow(GET /group-types/{typeKey}/workflow),按钮按出边渲染
export type TypeWorkflow = {
  name: string;
  statusType: string;
  transitions: { function: string; target: string }[];
};
export type Candidate = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  estimateValue?: number | null; // YPJ-216:已有预估的票不重录(重录会触发互算改 due)
  estimateUnit?: string | null;
};
// 0106:组完成后的非终态成员处置(rows 默认全转入目标组,逐行可覆写回 Backlog;D2/D3)
export type Disp = {
  rows: { itemId: string; title: string; toBacklog: boolean }[];
  actives: Sprint[];
  planned: Sprint[];
  typeName: string;
  pick: string;
  newName: string;
};
