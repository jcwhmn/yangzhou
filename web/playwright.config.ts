import { defineConfig } from "@playwright/test";
import { config } from "./e2e/config";

/**
 * E2E:并行、按用例隔离(project fixture 唯一 key,数据走 API 预置)。
 * 前置:本机 Postgres(共享 compose)+ 后端 test profile:
 *   gradle :api:bootRun --args='--spring.profiles.active=test'
 * 前端 dev server 由 webServer 拉起(已在跑则复用)。
 * 凭证/地址见 e2e/.env(模板 .env.example)。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  fullyParallel: true,
  workers: process.env.CI ? 2 : undefined,
  use: {
    baseURL: config.baseURL,
    locale: "zh-CN",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: {
    // 双 profile 并存(YPJ-3):日常 3000→8081;E2E 可用 E2E_FRONT_PORT 另起临时前端(配 E2E_BASE_URL + BACKEND_URL 指 test profile)
    command: process.env.E2E_FRONT_PORT ? `npx next dev -p ${process.env.E2E_FRONT_PORT}` : "npm run dev",
    url: config.baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
