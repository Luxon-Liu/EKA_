"use client";

// 主页面：未登录守卫 → 拉当前用户确认登录态，未登录跳登录页；
// 已登录才挂载会话提供者与聊天界面，避免未登录时业务请求报错
import { Thread } from "@/components/thread";
import { EkaProvider } from "@/providers/Eka";
import { ThreadProvider } from "@/providers/Thread";
import { ArtifactProvider } from "@/components/thread/artifact";
import { Toaster } from "@/components/ui/sonner";
import { fetchCurrentUser } from "@/lib/auth";
import { useRouter } from "next/navigation";
import React from "react";

export default function DemoPage(): React.ReactNode {
  const router = useRouter();
  // 登录态：null 表示尚未确认，false 表示确认未登录，true 表示已登录
  const [authed, setAuthed] = React.useState<boolean | null>(null);

  // 进入页面先向后端确认登录态：前端 JS 跨端口读不到后端写入的 Cookie，
  // 只能靠 /auth/me 的 401 结果判断，未登录则跳登录页并带上回跳地址
  React.useEffect(() => {
    let alive = true;
    fetchCurrentUser().then((user) => {
      if (!alive) return;
      if (user) {
        setAuthed(true);
      } else {
        setAuthed(false);
        router.replace(`/login?next=${encodeURIComponent("/")}`);
      }
    });
    return () => {
      alive = false;
    };
  }, [router]);

  // 登录态未确认或未登录时不渲染主界面，避免业务请求报错
  if (authed !== true) {
    return <div className="p-6 text-sm text-gray-500">加载中...</div>;
  }

  return (
    <React.Suspense fallback={<div>加载中...</div>}>
      <Toaster />
      <ThreadProvider>
        <EkaProvider>
          <ArtifactProvider>
            <Thread />
          </ArtifactProvider>
        </EkaProvider>
      </ThreadProvider>
    </React.Suspense>
  );
}
