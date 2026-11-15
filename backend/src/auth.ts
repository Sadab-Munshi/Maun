// The HTTP side of a member-only relay: the email login the app uses, and the
// administrator's page with the routes behind it.
//
//   POST /v1/auth/start   {email}             sends a login code
//   POST /v1/auth/verify  {email, code}       -> {ticket, username}
//   POST /v1/auth/name    {ticket, username}  a new member picks a username
//
//   GET  /admin                               the page
//   POST /v1/admin/login  {email, code}       sets the session cookie
//   GET  /v1/admin/members
//   POST /v1/admin/members {action, email}
//   POST /v1/admin/logout

import { adminPage } from "./admin-page";
import { tooManyRequests } from "./mailbox";
import {
  type AdminAction,
  type MemberConfig,
  SESSION_TTL_MS,
  adminAction,
  checkCode,
  endSession,
  listMembers,
  memberConfig,
  nameTicket,
  newSession,
  newTicket,
  normalEmail,
  sessionValid,
  startCode,
} from "./members";

const MAX_BODY_CHARS = 2048;
const COOKIE = "maun_admin";
const ACTIONS: AdminAction[] = ["approve", "ban", "unban", "remove", "add"];

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  Response.json(body, { status, headers: { "Cache-Control": "no-store", ...headers } });

/** Reads a small JSON object. Null when the body is anything else. */
async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  if (!(request.headers.get("Content-Type") ?? "").toLowerCase().startsWith("application/json")) return null;
  const text = await request.text();
  if (text.length > MAX_BODY_CHARS) return null;
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const text = (value: unknown, max: number): string | null =>
  typeof value === "string" && value.length > 0 && value.length <= max ? value : null;

/** Answers the routes above. Null when the request is for something else. */
export async function handleAuth(request: Request, env: Env, url: URL): Promise<Response | null> {
  const path = url.pathname;
  if (path !== "/admin" && !path.startsWith("/v1/auth/") && !path.startsWith("/v1/admin/")) return null;
  const config = memberConfig(env);

  if (request.method === "GET" && path === "/admin") return adminPage(config !== null);
  if (!config) return json({ error: "not_member_only" }, 404);

  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const now = Date.now();

  if (request.method === "POST" && path.startsWith("/v1/auth/")) {
    const body = await readBody(request);
    if (!body) return json({ error: "bad_request" }, 400);

    if (path === "/v1/auth/start") {
      const email = normalEmail(body.email);
      if (!email) return json({ error: "bad_email" }, 400);
      // Each of these makes the relay send an email to an address of the caller's choosing.
      if (!(await env.SIGNUP_LIMIT.limit({ key: ip })).success) return tooManyRequests();
      const outcome = await startCode(env, config, email, now);
      if (outcome === "sent") return json({ ok: true });
      if (outcome === "mail_failed") return json({ error: "mail_failed" }, 502);
      return json({ error: outcome }, 429, { "Retry-After": outcome === "wait" ? "60" : "3600" });
    }

    if (!(await env.CONNECT_LIMIT.limit({ key: ip })).success) return tooManyRequests();

    if (path === "/v1/auth/verify") {
      const email = normalEmail(body.email);
      const code = text(body.code, 12)?.replace(/\s/g, "");
      if (!email || !code || !/^[0-9]{6}$/.test(code)) return json({ error: "wrong_code" }, 401);
      const outcome = await checkCode(env, email, code, now);
      if (outcome !== "ok") return json({ error: outcome === "wrong" ? "wrong_code" : "expired" }, 401);
      return json(await newTicket(env, email, now));
    }

    if (path === "/v1/auth/name") {
      const ticket = text(body.ticket, 64);
      const username = text(body.username, 32)?.trim().toLowerCase();
      if (!ticket || !username) return json({ error: "bad_name" }, 400);
      const outcome = await nameTicket(env, ticket, username, now);
      if (outcome === "ok") return json({ ok: true, username });
      return json({ error: outcome }, outcome === "taken" ? 409 : outcome === "expired" ? 401 : 400);
    }
    return json({ error: "not_found" }, 404);
  }

  if (path.startsWith("/v1/admin/")) return admin(request, env, config, url, ip, now);
  return json({ error: "not_found" }, 404);
}

async function admin(request: Request, env: Env, config: MemberConfig, url: URL, ip: string, now: number): Promise<Response> {
  const path = url.pathname;
  if (request.method === "POST") {
    // The cookie is only sent from this site's own pages; this turns away anything else that slips through.
    const origin = request.headers.get("Origin");
    if (origin !== null && origin !== url.origin) return json({ error: "forbidden" }, 403);
  }

  if (request.method === "POST" && path === "/v1/admin/login") {
    if (!(await env.SIGNUP_LIMIT.limit({ key: `admin:${ip}` })).success) return tooManyRequests();
    const body = await readBody(request);
    const email = normalEmail(body?.email);
    const code = text(body?.code, 12)?.replace(/\s/g, "");
    // Someone else's address gets the same answer as a wrong code.
    if (!email || !code || email !== config.admin) return json({ error: "wrong_code" }, 401);
    const outcome = await checkCode(env, email, code, now);
    if (outcome !== "ok") return json({ error: outcome === "wrong" ? "wrong_code" : "expired" }, 401);
    const token = await newSession(env, now);
    const cookie = `${COOKIE}=${token}; Path=/v1/admin; Max-Age=${SESSION_TTL_MS / 1000}; HttpOnly; Secure; SameSite=Strict`;
    return json({ ok: true }, 200, { "Set-Cookie": cookie });
  }

  const token = sessionToken(request);
  if (!token || !(await sessionValid(env, token, now))) return json({ error: "login_required" }, 401);

  if (request.method === "GET" && path === "/v1/admin/members") {
    return json({ admin: config.admin, ...(await listMembers(env, config, now)) });
  }
  if (request.method === "POST" && path === "/v1/admin/members") {
    const body = await readBody(request);
    const email = normalEmail(body?.email);
    const action = ACTIONS.find((known) => known === body?.action);
    if (!email || !action) return json({ error: "bad_request" }, 400);
    const outcome = await adminAction(env, config, action, email, now);
    return outcome === "done" ? json({ ok: true }) : json({ error: "refused" }, 409);
  }
  if (request.method === "POST" && path === "/v1/admin/logout") {
    await endSession(env, token);
    return json({ ok: true }, 200, { "Set-Cookie": `${COOKIE}=; Path=/v1/admin; Max-Age=0; HttpOnly; Secure; SameSite=Strict` });
  }
  return json({ error: "not_found" }, 404);
}

function sessionToken(request: Request): string | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const [name, value] = part.trim().split("=");
    if (name === COOKIE && value && /^[A-Za-z0-9_-]{20,64}$/.test(value)) return value;
  }
  return null;
}
