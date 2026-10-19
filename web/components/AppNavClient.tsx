"use client";

import dynamic from "next/dynamic";

// YPJ-52 flake 根因修复:AppNav 含受控跳转框,SSR 直出 + hydration 前输入会被 state 重渲染抹掉
// (e2e number-jump 偶发「格式:项目KEY-编号」)。客户端-only 渲染,保证可交互时必已接管。
export const AppNav = dynamic(() => import("@/components/AppNav").then((m) => m.AppNav), { ssr: false });
