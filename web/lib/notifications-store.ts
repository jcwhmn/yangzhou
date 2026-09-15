"use client";

import { api } from "@/lib/api";

/**
 * V9-Q4:未读数模块级单例 store。
 * 全局唯一 30s 定时器 + focus 刷新;订阅者(AppNav)只管显示;通知页已读后 bump()。
 */
type Listener = (count: number) => void;

let count = 0;
let timer: ReturnType<typeof setInterval> | null = null;
const listeners = new Set<Listener>();
let inflight = false;

function hasToken(): boolean {
  return typeof window !== "undefined" && !!localStorage.getItem("yz-token");
}

async function refresh(): Promise<void> {
  if (!hasToken() || inflight) return;
  inflight = true;
  try {
    const res = await api<{ count: number }>("/api/notifications/unread-count");
    set(res.count);
  } catch {
    /* 静默:铃铛只是提示 */
  } finally {
    inflight = false;
  }
}

function set(v: number) {
  if (v === count) return;
  count = v;
  listeners.forEach((l) => l(count));
}

/** 通知页已读等操作后调用,强制重拉。 */
export function bumpUnread(): void {
  void refresh();
}

export function subscribeUnread(l: Listener): () => void {
  listeners.add(l);
  l(count);
  if (!timer) {
    timer = setInterval(refresh, 30000);
    void refresh();
    if (typeof window !== "undefined") {
      window.addEventListener("focus", refresh);
    }
  }
  return () => {
    listeners.delete(l);
    if (listeners.size === 0 && timer) {
      clearInterval(timer);
      timer = null;
    }
  };
}
