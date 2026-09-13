import { defineConfig } from "@playwright/test";

/**
 * E2E 核心链路(V7 后安全网)。
 * 前置:本机 Postgres(共享 compose)+ 后端 `gradle :api:bootRun`(8080)。
 * Next dev 由 webServer 拉起(已在跑则复用)。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: 0,
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    locale: "zh-CN",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
