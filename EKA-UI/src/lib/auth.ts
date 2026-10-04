// 登录鉴权前端工具：token 由后端写入 Cookie（eka_token），这里提供 Cookie 读写、
// 登录态判断与用户信息查询。所有请求都必须带 credentials: "include"，否则跨域下浏览器不带 Cookie。
import { BASE } from "./eka-api";

/** 后端写入的登录 Cookie 名：与 application.yml 的 auth.cookie-name 一致 */
export const TOKEN_COOKIE = "eka_token";

/** 登录用户信息：与后端 LoginUserVO 一一对应 */
export interface LoginUser {
  userId: string;
  username: string;
}

/** 读取指定 Cookie 值 */
export function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const prefix = `${name}=`;
  const hit = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return hit ? decodeURIComponent(hit.slice(prefix.length)) : null;
}

/** 当前是否已登录：仅凭本地 Cookie 是否存在判断，真伪由后端过滤器定夺 */
export function hasToken(): boolean {
  const token = readCookie(TOKEN_COOKIE);
  return !!token && token.length > 0;
}

/** 登录/注册等认证接口的统一响应解包：非 200 抛出后端中文提示 */
async function unwrap<T>(resp: Response): Promise<T> {
  const body = await resp.json().catch(() => null);
  if (!resp.ok || body?.code !== 200) {
    throw new Error(body?.message || `请求失败：${resp.status}`);
  }
  return body.data as T;
}

/** 登录：账号密码走后端校验，成功后后端把 JWT 写入 Cookie */
export function login(username: string, password: string): Promise<LoginUser> {
  const form = new URLSearchParams({ username, password });
  return fetch(`${BASE}/auth/login`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form,
  }).then((r) => unwrap<LoginUser>(r));
}

/** 注册新账号 */
export function register(
  username: string,
  password: string,
): Promise<LoginUser> {
  const form = new URLSearchParams({ username, password });
  return fetch(`${BASE}/auth/register`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form,
  }).then((r) => unwrap<LoginUser>(r));
}

/** 登出：后端清空 Cookie */
export function logout(): Promise<void> {
  return fetch(`${BASE}/auth/logout`, {
    method: "POST",
    credentials: "include",
  }).then(() => undefined);
}

/** 查当前登录用户：token 失效时后端返回 401，这里返回 null 交由调用方跳登录页 */
export async function fetchCurrentUser(): Promise<LoginUser | null> {
  const resp = await fetch(`${BASE}/auth/me`, { credentials: "include" });
  if (!resp.ok) return null;
  const body = await resp.json().catch(() => null);
  if (body?.code !== 200) return null;
  return body.data as LoginUser;
}
