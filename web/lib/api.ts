// API 瘦客户端:token 存 localStorage;401 → 登录页。
// 默认同源(经 Next rewrites 代理到后端,浏览器零 CORS);自部署 API 时用 NEXT_PUBLIC_API_URL 覆盖。
import { setDown, setUp } from "@/lib/connection-store";

export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "";

// 连接失败退避重试间隔(0104):仍失败才置全局不可达
const RETRY_DELAYS_MS = [1000, 2000];

/** 连接失败(TypeError/代理 502/504)退避重试;耗尽则 setDown + 抛 ApiError(0)。业务错误不进这里。 */
async function fetchWithRetry(
  path: string,
  init: RequestInit,
): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(`${API_BASE}${path}`, init);
      if (res.status !== 502 && res.status !== 504) {
        setUp(); // 有响应即可达,横幅自愈
        return res;
      }
    } catch (e) {
      if (!(e instanceof TypeError)) throw e; // abort 等非网络层错误不重试
    }
    if (attempt >= RETRY_DELAYS_MS.length) {
      setDown();
      throw new ApiError(0, "后端不可达");
    }
    await new Promise((r) => setTimeout(r, RETRY_DELAYS_MS[attempt]));
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function api<T = unknown>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const token = localStorage.getItem("yz-token");
  const res = await fetchWithRetry(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });
  if (res.status === 401 && typeof window !== "undefined") {
    // 过期/无效 token:清掉,避免 login 页 401→跳转 死循环(V9-S2 铃铛后台请求引入)
    localStorage.removeItem("yz-token");
    if (window.location.pathname !== "/login") window.location.href = "/login";
    throw new ApiError(401, "未认证");
  }
  if (!res.ok) {
    let message = `${res.status}`;
    try {
      const body = await res.json();
      message = body.message ?? message;
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(res.status, message);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export function setToken(token: string) {
  localStorage.setItem("yz-token", token);
}

export function clearToken() {
  localStorage.removeItem("yz-token");
}

/** 全新服务器 bootstrap,否则 login(同 CLI 语义)。 */
export async function bootstrapOrLogin(
  username: string,
  password: string,
): Promise<string> {
  const post = () =>
    fetch(`${API_BASE}/api/auth/bootstrap`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  let res = await post();
  if (res.status === 409) {
    res = await fetch(`${API_BASE}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  }
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ message: String(res.status) }));
    throw new ApiError(res.status, body.message ?? "登录失败");
  }
  const data = (await res.json()) as { token: string };
  return data.token;
}
