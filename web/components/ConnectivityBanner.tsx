"use client";

// 全局连接横幅(0104):后端不可达时常驻页面顶部;任一请求成功自愈(见 connection-store)。
// 挂在 app/layout.tsx 根,登录页也覆盖(bootstrap 失败同样提示)。
import { Alert } from "@mui/material";
import { useEffect, useState } from "react";
import { subscribeConnection } from "@/lib/connection-store";
import { t } from "@/lib/texts";

export function ConnectivityBanner() {
  const [down, setDown] = useState(false);
  useEffect(() => subscribeConnection(setDown), []);
  if (!down) return null;
  return (
    <Alert severity="warning" role="alert">
      {t.connection.down}
    </Alert>
  );
}
