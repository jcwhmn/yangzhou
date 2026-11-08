// spec 0115(YPJ-164):组动作函数数据字典——与后端 GroupActionFn 注册表对齐。
// fn = Kotlin enum 名小写(transitions JSON 的存储名,下拉显示英文);
// label = 中文显示名(编辑态预填,用户可改);icon = 预定义符号(icon 下拉选项)。
export type WfFnDef = { label: string; icon: string };

export const WF_FN_DICT: Record<string, WfFnDef> = {
  start: { label: "开始", icon: "▶" },
  complete: { label: "完成", icon: "✓" },
  backward: { label: "后退", icon: "←" },
  switch: { label: "切换", icon: "↔" },
  forward: { label: "前进", icon: "→" },
  reopen: { label: "重开", icon: "↺" },
  cancel: { label: "取消", icon: "✕" },
};

export function wfFnLabel(fn: string): string {
  return WF_FN_DICT[fn]?.label ?? fn;
}

export function wfFnIcon(fn: string): string {
  return WF_FN_DICT[fn]?.icon ?? "";
}
