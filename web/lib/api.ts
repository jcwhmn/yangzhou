// API 瘦客户端:token 存 localStorage;401 → 登录页。
// 默认同源(经 Next rewrites 代理到后端,浏览器零 CORS);自部署 API 时用 NEXT_PUBLIC_API_URL 覆盖。
export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function api<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem("yz-token");
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });
  if (res.status === 401 && typeof window !== "undefined") {
    window.location.href = "/login";
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
export async function bootstrapOrLogin(username: string, password: string): Promise<string> {
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
    const body = await res.json().catch(() => ({ message: String(res.status) }));
    throw new ApiError(res.status, body.message ?? "登录失败");
  }
  const data = (await res.json()) as { token: string };
  return data.token;
}
