// E2E 运行配置:.env 可覆盖(见 .env.example),未配置时回落本机默认值
import fs from "node:fs";
import path from "node:path";

const envFile = path.join(__dirname, ".env");
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}

export const config = {
  /** 前端 dev server(Playwright baseURL / webServer 探活) */
  baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
  /** 后端 API(用例的 API 预置/清理直连;页面请求走前端代理) */
  apiURL: process.env.E2E_API_URL ?? "http://localhost:8080",
  username: process.env.E2E_USERNAME ?? "me",
  password: process.env.E2E_PASSWORD ?? "secret",
};
