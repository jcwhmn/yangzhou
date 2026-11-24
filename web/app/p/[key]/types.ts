import type { Signal } from "@/components/Verdict";

export type Status = {
  statusId: string;
  name: string;
  icon: string | null;
  statusType: string;
  position: number;
  requireCloseReason: boolean; // YPJ-275:迁出到终态时前端须弹窗问关闭理由
};
export type Item = {
  itemId: string;
  parentItemId?: string | null;
  parentNumber?: string | null;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  assigneeColor: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
  feasSignal: Signal | null;
  dueDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  estimateValue: number | null; // YPJ-186 预估快照
  boardOrder: number | null; // YPJ-238(spec 0166):看板排序记忆(null=未排过,沉底按编号)
  estimateUnit: string | null;
  blocked: boolean;
  sprintName: string | null;
  closeReason: string | null; // YPJ-275:终态票恒非空(后端回填 done);非终态 null
  closeNote: string | null;
};

export type Filter = "all" | "unassigned" | "blocked" | string; // string = memberId
export type SprintLite = {
  sprintId: string;
  name: string;
  status: string;
  parentGroupId: string | null;
};
