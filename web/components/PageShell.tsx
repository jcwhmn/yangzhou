import type { ReactNode } from "react";
import { Box } from "@mui/material";

/** 全站页面容器:统一全宽靠左(YPJ-30)。布局将来变更只改这里。 */
export function PageShell({ children }: { children: ReactNode }) {
  return <Box sx={{ p: 3 }}>{children}</Box>;
}
