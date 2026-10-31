import { t } from "./texts";

// 详情页返回目标解析(YPJ-31):?from= 白名单 token → 来源页;非法/缺失 fallback 看板。
// 字符集不含 / . :,排除路径穿越与协议相对 URL(开放跳转)。
const FROM_RE = /^(backlog|table|gantt|search|sprint-[0-9a-zA-Z-]{1,64})$/;

export function resolveBack(
  from: string | null,
  projectKey: string,
): { href: string; label: string } {
  if (!from || !FROM_RE.test(from))
    return { href: `/p/${projectKey}`, label: t.item.back };
  if (from === "search")
    return { href: "/search", label: t.item.backTo.search };
  if (from === "backlog")
    return { href: `/p/${projectKey}/backlog`, label: t.item.backTo.backlog };
  if (from === "table")
    return { href: `/p/${projectKey}/table`, label: t.item.backTo.table };
  if (from === "gantt")
    return { href: `/p/${projectKey}/gantt`, label: t.item.backTo.gantt };
  return {
    href: `/p/${projectKey}/sprint/${from.slice(7)}`,
    label: t.item.backTo.sprint,
  };
}
