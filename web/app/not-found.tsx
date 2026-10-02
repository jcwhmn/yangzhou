"use client";

import { Button, Stack, Typography } from "@mui/material";
import { useRouter } from "next/navigation";

/** 全局 404:给返回上一页按钮,不依赖浏览器后退(V12-S3 用户反馈)。 */
export default function NotFound() {
  const router = useRouter();
  return (
    <Stack spacing={2} alignItems="center" sx={{ py: 12 }}>
      <Typography variant="h4">404</Typography>
      <Typography color="text.secondary">页面不存在或已被移除。</Typography>
      <Button variant="contained" onClick={() => router.back()}>
        ← 返回上一页
      </Button>
    </Stack>
  );
}
