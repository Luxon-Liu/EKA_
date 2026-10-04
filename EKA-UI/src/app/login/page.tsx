"use client";

// 登录/注册页：苹果官网式深色高质感风格。
// 纯黑背景 + 顶部超大渐变标题 + 玻璃拟态登录卡片 + 胶囊按钮；
// 登录态由后端写入 Cookie 承载，这里只管表单提交与跳转。
import React from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { login, register } from "@/lib/auth";

type Mode = "login" | "register";

function LoginPageContent(): React.ReactNode {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [mode, setMode] = React.useState<Mode>("login");
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  // 登录/注册成功后的回跳地址：守卫跳转时带过来的 next 参数，缺省回首页
  const redirectTo = searchParams.get("next") || "/";

  // 提交表单：按当前 Tab 调登录或注册接口，成功后跳回目标页
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      if (mode === "login") {
        await login(username.trim(), password);
      } else {
        await register(username.trim(), password);
      }
      router.replace(redirectTo);
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败，请重试");
    } finally {
      setSubmitting(false);
    }
  };

  // 切换 Tab 时清掉残留的提示信息
  const switchMode = (next: Mode) => {
    setMode(next);
    setError(null);
  };

  // 输入框统一样式：深灰内嵌底、无边框、聚焦发亮，苹果设置页风格
  const inputStyle: React.CSSProperties = {
    width: "100%",
    height: "46px",
    padding: "0 16px",
    borderRadius: "12px",
    background: "rgba(255,255,255,0.07)",
    border: "1px solid rgba(255,255,255,0.1)",
    color: "#f5f5f7",
    fontSize: "15px",
    outline: "none",
    transition: "border-color .2s ease, background .2s ease",
  };

  return (
    <div
      className="relative flex min-h-screen items-center justify-center overflow-hidden px-4"
      style={{
        background:
          "radial-gradient(1200px 600px at 50% -10%, rgba(120,119,255,0.16) 0%, rgba(0,0,0,0) 55%), radial-gradient(900px 500px at 85% 100%, rgba(0,180,214,0.10) 0%, rgba(0,0,0,0) 50%), #000000",
      }}
    >
      <div style={{ width: "100%", maxWidth: "400px" }}>
        {/* 品牌标题：白到灰的大字号渐变，苹果发布会式排版 */}
        <div className="mb-10 text-center">
          <h1
            style={{
              fontSize: "34px",
              lineHeight: 1.2,
              fontWeight: 700,
              letterSpacing: "-0.02em",
              background: "linear-gradient(180deg, #ffffff 30%, #86868b)",
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              color: "transparent",
            }}
          >
            政务档案利用与分析智能体
          </h1>
          <p
            style={{
              marginTop: "10px",
              fontSize: "13px",
              letterSpacing: "0.35em",
              color: "#6e6e73",
            }}
          >
            EKA
          </p>
        </div>

        {/* 玻璃拟态卡片：磨砂背景 + 发丝描边 + 大圆角 */}
        <div
          style={{
            padding: "32px 28px",
            borderRadius: "24px",
            background: "rgba(255,255,255,0.05)",
            border: "1px solid rgba(255,255,255,0.09)",
            backdropFilter: "blur(24px)",
            WebkitBackdropFilter: "blur(24px)",
            boxShadow:
              "0 24px 70px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.08)",
          }}
        >
          {/* 登录/注册分段切换器：黑玻璃上的白色滑块 */}
          <div
            className="mb-7"
            style={{
              display: "grid",
              gridTemplateColumns: "1fr 1fr",
              gap: "4px",
              padding: "4px",
              borderRadius: "12px",
              background: "rgba(255,255,255,0.06)",
              border: "1px solid rgba(255,255,255,0.08)",
            }}
          >
            {(["login", "register"] as Mode[]).map((m) => {
              const active = mode === m;
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => switchMode(m)}
                  style={{
                    padding: "9px 0",
                    borderRadius: "9px",
                    fontSize: "14px",
                    fontWeight: 500,
                    color: active ? "#000000" : "#a1a1a6",
                    background: active
                      ? "linear-gradient(180deg, #ffffff, #d6d6db)"
                      : "transparent",
                    boxShadow: active ? "0 1px 6px rgba(255,255,255,0.25)" : "none",
                    transition: "all .25s ease",
                    cursor: "pointer",
                  }}
                >
                  {m === "login" ? "登录" : "注册"}
                </button>
              );
            })}
          </div>

          <form onSubmit={handleSubmit}>
            <div style={{ marginBottom: "18px" }}>
              <label style={{ display: "block", fontSize: "13px", color: "#a1a1a6", marginBottom: "8px" }}>
                账号
              </label>
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入账号"
                autoComplete="username"
                required
                style={inputStyle}
                onFocus={(e) => {
                  e.target.style.borderColor = "rgba(255,255,255,0.35)";
                  e.target.style.background = "rgba(255,255,255,0.1)";
                }}
                onBlur={(e) => {
                  e.target.style.borderColor = "rgba(255,255,255,0.1)";
                  e.target.style.background = "rgba(255,255,255,0.07)";
                }}
              />
            </div>

            <div style={{ marginBottom: "22px" }}>
              <label style={{ display: "block", fontSize: "13px", color: "#a1a1a6", marginBottom: "8px" }}>
                密码
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
                autoComplete={
                  mode === "login" ? "current-password" : "new-password"
                }
                required
                style={inputStyle}
                onFocus={(e) => {
                  e.target.style.borderColor = "rgba(255,255,255,0.35)";
                  e.target.style.background = "rgba(255,255,255,0.1)";
                }}
                onBlur={(e) => {
                  e.target.style.borderColor = "rgba(255,255,255,0.1)";
                  e.target.style.background = "rgba(255,255,255,0.07)";
                }}
              />
            </div>

            {error && (
              <p
                role="alert"
                style={{
                  marginBottom: "14px",
                  fontSize: "13px",
                  color: "#ff6961",
                  textAlign: "center",
                }}
              >
                {error}
              </p>
            )}

            {/* 提交按钮：白色胶囊，悬停微放大 */}
            <button
              type="submit"
              disabled={submitting}
              style={{
                width: "100%",
                height: "48px",
                borderRadius: "999px",
                border: "none",
                fontSize: "15px",
                fontWeight: 600,
                color: mode === "login" ? "#000000" : "#86868b",
                background:
                  mode === "login"
                    ? "linear-gradient(180deg, #ffffff, #d6d6db)"
                    : "linear-gradient(180deg, #2c2c2e, #1c1c1e)",
                boxShadow:
                  mode === "login"
                    ? "0 1px 10px rgba(255,255,255,0.2)"
                    : "none",
                cursor: submitting ? "wait" : "pointer",
                opacity: submitting ? 0.7 : 1,
                transition: "transform .2s ease, opacity .2s ease",
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = "scale(1.02)";
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = "scale(1)";
              }}
            >
              {submitting
                ? "请稍候..."
                : mode === "login"
                  ? "登录"
                  : "注册并登录"}
            </button>
          </form>
        </div>

        <p
          style={{
            marginTop: "28px",
            textAlign: "center",
            fontSize: "12px",
            color: "#48484a",
          }}
        >
          企业内部使用 · 请保管好账号密码
        </p>
      </div>
    </div>
  );
}

export default function LoginPage(): React.ReactNode {
  return (
    <React.Suspense fallback={<div>加载中...</div>}>
      <LoginPageContent />
    </React.Suspense>
  );
}
