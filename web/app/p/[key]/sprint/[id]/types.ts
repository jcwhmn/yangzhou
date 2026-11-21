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
};
