"use client";

import { Button, Stack, Typography } from "@mui/material";
import { useRouter } from "next/navigation";

/** 全局运行时错误边界:返回上一页 / 重试(V12-S3 用户反馈)。 */
export default function GlobalErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();
  return (
    <Stack spacing={2} alignItems="center" sx={{ py: 12 }}>
      <Typography variant="h5">页面出错了</Typography>
      <Typography color="text.secondary">
        {error.message || "发生了意外错误。"}
      </Typography>
      <Stack direction="row" spacing={2}>
        <Button variant="contained" onClick={() => router.back()}>
          ← 返回上一页
        </Button>
        <Button onClick={reset}>重试</Button>
      </Stack>
    </Stack>
  );
}
