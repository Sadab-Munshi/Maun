import { SELF, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CODES_PER_DAY, CODE_ATTEMPTS, CODE_GAP_MS, CODE_TTL_MS, MAIL_DAILY_CAP, forgetSchema } from "../../src/members";
import { CLOSE_ACCESS_REQUIRED, CLOSE_BANNED, CLOSE_PENDING, MEMBER_CHECK_MS, isUsername } from "../../src/protocol";
import {
  connect,
  freshIp,
  hangUp,
  keysFrame,
  loginFrame,
  makeUser,
  randomBytes,
  registerFrame,
  requestId,
  type Inbox,
  type User,
} from "../helpers";

// This file runs with ADMIN_EMAIL, RESEND_API_KEY and MAIL_FROM set
// (vitest.members.config.ts): the relay is member-only.

const ADMIN = "boss@example.com";

type Mail = { key: string | null; from: string; to: string; subject: string; text: string };

/** Every email the relay handed to the mail service, and what the service answers. */
let mails: Mail[] = [];
let mailStatus = 200;

beforeEach(async () => {
  mails = [];
  mailStatus = 200;
  // The tests of one file share a database: start each with an empty one.
  const tables = ["users", "devices", "codes", "tickets", "sessions", "mail_days"];
  await env.DB.batch(tables.map((table) => env.DB.prepare(`DROP TABLE IF EXISTS ${table}`)));
  forgetSchema();
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url !== "https://api.resend.com/emails") return new Response("unexpected", { status: 500 });
    const body = JSON.parse(init?.body as string) as { from: string; to: string[]; subject: string; text: string };
    const headers = new Headers(init?.headers);
    if (mailStatus === 200) {
      mails.push({ key: headers.get("Authorization"), from: body.from, to: body.to[0]!, subject: body.subject, text: body.text });
    }
    return Response.json({ id: "mail" }, { status: mailStatus });
  });
});

afterEach(() => vi.restoreAllMocks());

function post(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return SELF.fetch(`https://relay.test${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": freshIp(), ...headers },
    body: JSON.stringify(body),
  });
}

/** Asks for a login code and reads it out of the email. */
async function codeFor(email: string): Promise<string> {
  const response = await post("/v1/auth/start", { email });
  expect(response.status).toBe(200);
  const mail = mails.filter((sent) => sent.to === email.toLowerCase()).at(-1)!;
  return /\b([0-9]{6})\b/.exec(mail.text)![1]!;
}

/** Goes through the email login. A new member's username is put on the ticket. */
async function ticketFor(email: string, username?: string): Promise<string> {
  const verified = await post("/v1/auth/verify", { email, code: await codeFor(email) });
  expect(verified.status).toBe(200);
  const { ticket, username: existing } = (await verified.json()) as { ticket: string; username: string | null };
  if (username && !existing) expect((await post("/v1/auth/name", { ticket, username })).status).toBe(200);
  return ticket;
}

async function tryRegister(user: User, access?: string): Promise<{ inbox: Inbox; answer: Record<string, unknown> }> {
  const { inbox, nonce, registered } = await connect(user.id);
  expect(registered).toBe(false);
  inbox.sendJson({ ...(await registerFrame(user.identity, user.device, nonce)), ...(access ? { access } : {}) });
  return { inbox, answer: await inbox.nextJson() };
}

async function tryLogin(user: User, access?: string): Promise<{ inbox: Inbox; answer: Record<string, unknown> }> {
  const { inbox, nonce } = await connect(user.id);
  inbox.sendJson({ ...(await loginFrame(user.identity, user.device, nonce)), ...(access ? { access } : {}) });
  return { inbox, answer: await inbox.nextJson() };
}

async function row(email: string): Promise<Record<string, unknown> | null> {
  try {
    return await env.DB.prepare("SELECT * FROM users WHERE email = ?").bind(email).first();
  } catch {
    return null;
  }
}

const set = (email: string, change: string) => env.DB.prepare(`UPDATE users SET ${change} WHERE email = ?`).bind(email).run();

/** Somebody who logged in with their email and is waiting for the administrator. */
async function applicant(email: string, username: string): Promise<User> {
  const user = await makeUser();
  const { inbox, answer } = await tryRegister(user, await ticketFor(email, username));
  expect(answer).toEqual({ t: "error", code: "pending" });
  expect((await inbox.closed).code).toBe(CLOSE_PENDING);
  return user;
}

/** An approved member with an account on the relay, offline. */
async function member(email: string, username: string): Promise<User> {
  const user = await applicant(email, username);
  await set(email, "status = 'approved'");
  const { inbox, answer } = await tryRegister(user);
  expect(answer).toMatchObject({ t: "ready", fresh: true, email, name: username });
  await hangUp(inbox);
  return user;
}

describe("a member-only relay", () => {
  it("says so before login", async () => {
    const user = await makeUser();
    const response = await SELF.fetch(`https://relay.test/v1/ws/${user.id}`, {
      headers: { Upgrade: "websocket", "CF-Connecting-IP": freshIp() },
    });
    const ws = response.webSocket!;
    const hello = new Promise<Record<string, unknown>>((resolve) =>
      ws.addEventListener("message", (event) => resolve(JSON.parse(event.data as string))),
    );
    ws.accept();
    expect(await hello).toMatchObject({ t: "hello", access: true });
    ws.close(1000, "done");
    expect(await (await SELF.fetch("https://relay.test/health")).json()).toMatchObject({ access: true });
  });

  it("turns away an ID that has not been through the email login, and keeps nothing of it", async () => {
    const user = await makeUser();
    for (const ticket of [undefined, "not-a-ticket", "A".repeat(43)]) {
      const { inbox, answer } = await tryRegister(user, ticket);
      expect(answer).toEqual({ t: "error", code: "access_required" });
      expect((await inbox.closed).code).toBe(CLOSE_ACCESS_REQUIRED);
    }
    const again = await connect(user.id);
    expect(again.registered).toBe(false);
    await hangUp(again.inbox);
  });
});

describe("the email login", () => {
  it("sends a code through the mail service and exchanges the right one for a ticket", async () => {
    const code = await codeFor("Asha@Example.com");
    expect(mails).toHaveLength(1);
    expect(mails[0]).toMatchObject({ key: "Bearer re_test_key", from: "Maun <login@maun.test>", to: "asha@example.com" });
    expect(mails[0]!.subject).toContain(code);

    const wrong = await post("/v1/auth/verify", { email: "asha@example.com", code: code === "000000" ? "000001" : "000000" });
    expect(wrong.status).toBe(401);
    expect(await wrong.json()).toEqual({ error: "wrong_code" });

    const right = await post("/v1/auth/verify", { email: "ASHA@example.com", code });
    expect(right.status).toBe(200);
    expect(await right.json()).toEqual({ ticket: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), username: null });

    // A code works once.
    const again = await post("/v1/auth/verify", { email: "asha@example.com", code });
    expect(again.status).toBe(401);
    expect(await again.json()).toEqual({ error: "expired" });
    // Neither the code nor the ticket is stored as such.
    const stored = JSON.stringify((await env.DB.prepare("SELECT * FROM codes").all()).results) +
      JSON.stringify((await env.DB.prepare("SELECT * FROM tickets").all()).results);
    expect(stored).not.toContain(code);
  });

  it("gives a code only so many tries, and only so long", async () => {
    const code = await codeFor("asha@example.com");
    const wrong = code === "111111" ? "222222" : "111111";
    for (let i = 0; i < CODE_ATTEMPTS; i++) {
      expect(await (await post("/v1/auth/verify", { email: "asha@example.com", code: wrong })).json()).toEqual({ error: "wrong_code" });
    }
    expect(await (await post("/v1/auth/verify", { email: "asha@example.com", code })).json()).toEqual({ error: "expired" });

    const late = await codeFor("rohan@example.com");
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + CODE_TTL_MS + 1000);
    expect(await (await post("/v1/auth/verify", { email: "rohan@example.com", code: late })).json()).toEqual({ error: "expired" });
  });

  it("does not let itself be used to flood an address", async () => {
    await codeFor("asha@example.com");
    const soon = await post("/v1/auth/start", { email: "asha@example.com" });
    expect(soon.status).toBe(429);
    expect(await soon.json()).toEqual({ error: "wait" });
    expect(mails).toHaveLength(1);

    const real = Date.now();
    for (let i = 1; i < CODES_PER_DAY; i++) {
      vi.spyOn(Date, "now").mockReturnValue(real + i * (CODE_GAP_MS + 1000));
      expect((await post("/v1/auth/start", { email: "asha@example.com" })).status).toBe(200);
    }
    vi.spyOn(Date, "now").mockReturnValue(real + CODES_PER_DAY * (CODE_GAP_MS + 1000));
    const over = await post("/v1/auth/start", { email: "asha@example.com" });
    expect(over.status).toBe(429);
    expect(await over.json()).toEqual({ error: "limit" });
    expect(mails).toHaveLength(CODES_PER_DAY);
  });

  it("stops sending when the day's allowance of emails is used up", async () => {
    await codeFor("first@example.com");
    await env.DB.prepare("UPDATE mail_days SET sent = ?").bind(MAIL_DAILY_CAP).run();
    const response = await post("/v1/auth/start", { email: "second@example.com" });
    expect(response.status).toBe(502);
    expect(mails).toHaveLength(1);
  });

  it("refuses what is not an email address, and says so when the mail could not be sent", async () => {
    for (const email of ["", "asha", "asha@example", "a b@example.com", "<x>@example.com", 5]) {
      expect((await post("/v1/auth/start", { email })).status, String(email)).toBe(400);
    }
    expect((await SELF.fetch("https://relay.test/v1/auth/start", { method: "POST", body: "email=a@b.co" })).status).toBe(400);

    mailStatus = 403;
    const failed = await post("/v1/auth/start", { email: "asha@example.com" });
    expect(failed.status).toBe(502);
    expect(await failed.json()).toEqual({ error: "mail_failed" });
    // No code is on file, so asking again right away is possible.
    mailStatus = 200;
    expect((await post("/v1/auth/start", { email: "asha@example.com" })).status).toBe(200);
  });

  it("sends a banned address nothing, without saying so", async () => {
    await member("someone@example.com", "someone"); // creates the tables
    await env.DB.prepare("INSERT INTO users (email, is_banned) VALUES ('early@example.com', 1)").run();
    const before = mails.length;
    expect((await post("/v1/auth/start", { email: "early@example.com" })).status).toBe(200);
    expect(mails).toHaveLength(before);
  });

  it("lets a new member pick a username nobody has", async () => {
    await member("asha@example.com", "asha");
    const verified = await post("/v1/auth/verify", { email: "rohan@example.com", code: await codeFor("rohan@example.com") });
    const { ticket } = (await verified.json()) as { ticket: string };
    const name = (username: unknown, with_ = ticket) => post("/v1/auth/name", { ticket: with_, username });

    for (const bad of ["ab", "1abc", "has space", "ünï", "x".repeat(21), "admin", "maun"]) {
      expect((await name(bad)).status, bad).toBe(400);
    }
    const taken = await name("Asha");
    expect(taken.status).toBe(409);
    expect(await taken.json()).toEqual({ error: "taken" });
    expect((await name("rohan", "A".repeat(43))).status).toBe(401);

    const ok = await name(" Rohan_7 ");
    expect(ok.status).toBe(200);
    expect(await ok.json()).toEqual({ ok: true, username: "rohan_7" });

    // A name somebody else is in the middle of taking is taken too.
    const other = await post("/v1/auth/verify", { email: "zoya@example.com", code: await codeFor("zoya@example.com") });
    const theirs = ((await other.json()) as { ticket: string }).ticket;
    expect((await name("rohan_7", theirs)).status).toBe(409);
  });

  it("knows what a username looks like", () => {
    for (const good of ["abc", "sadab", "a_b_c", "user2026", "a".repeat(20)]) expect(isUsername(good), good).toBe(true);
    for (const bad of ["", "ab", "_abc", "Abc", "a-b", "a.b", "a".repeat(21), "admin"]) expect(isUsername(bad), bad).toBe(false);
  });
});

describe("joining", () => {
  it("makes a new member wait for the administrator, who is told by email", async () => {
    const user = await applicant("Asha@Example.com", "asha");
    expect(await row("asha@example.com")).toMatchObject({ maun_id: user.id, username: "asha", status: "pending", is_banned: 0 });

    // The note goes out after the phone has had its answer.
    await vi.waitFor(() => expect(mails.at(-1)!.to).toBe(ADMIN));
    const note = mails.at(-1)!;
    expect(note.text).toContain("asha@example.com");
    expect(note.text).toContain("https://relay.test/admin");

    // Nothing was made on the relay for them yet, and coming back changes nothing: no second email.
    const count = mails.length;
    const again = await tryRegister(user);
    expect(again.answer).toEqual({ t: "error", code: "pending" });
    expect((await again.inbox.closed).code).toBe(CLOSE_PENDING);
    expect(mails).toHaveLength(count);

    await set("asha@example.com", "status = 'approved'");
    const { inbox, answer } = await tryRegister(user);
    expect(answer).toMatchObject({ t: "ready", fresh: true, email: "asha@example.com", name: "asha" });
    await hangUp(inbox);

    // From now on the phone's own key is enough.
    const later = await tryLogin(user);
    expect(later.answer).toMatchObject({ t: "ready", fresh: false, email: "asha@example.com", name: "asha" });
    await hangUp(later.inbox);
  });

  it("lets the administrator, and anyone approved in advance, straight in", async () => {
    const boss = await makeUser();
    const first = await tryRegister(boss, await ticketFor(ADMIN, "boss"));
    expect(first.answer).toMatchObject({ t: "ready", email: ADMIN, name: "boss" });
    await hangUp(first.inbox);

    await env.DB.prepare("INSERT INTO users (email, status, created_at) VALUES ('invited@example.com', 'approved', 1)").run();
    const invited = await makeUser();
    const second = await tryRegister(invited, await ticketFor("invited@example.com", "invited"));
    expect(second.answer).toMatchObject({ t: "ready", email: "invited@example.com", name: "invited" });
    await hangUp(second.inbox);
  });

  it("needs a username from a new member", async () => {
    const user = await makeUser();
    const { inbox, answer } = await tryRegister(user, await ticketFor("asha@example.com"));
    expect(answer).toEqual({ t: "error", code: "access_required" });
    await inbox.closed;
    expect(await row("asha@example.com")).toBeNull();
  });

  it("uses a ticket once", async () => {
    const ticket = await ticketFor("asha@example.com", "asha");
    const first = await makeUser();
    expect((await tryRegister(first, ticket)).answer).toEqual({ t: "error", code: "pending" });
    const second = await makeUser();
    expect((await tryRegister(second, ticket)).answer).toEqual({ t: "error", code: "access_required" });
    expect(await row("asha@example.com")).toMatchObject({ maun_id: first.id });
  });

  it("does not let a ticket tie an email address to an ID its bearer does not hold", async () => {
    const victim = await makeUser();
    const thief = await makeUser();
    const ticket = await ticketFor("thief@example.com", "thief");
    const { inbox, nonce } = await connect(victim.id);
    // Signed with the thief's keys, for the victim's address.
    inbox.sendJson({ ...(await registerFrame(thief.identity, thief.device, nonce)), access: ticket });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_signature" });
    await inbox.closed;
    expect(await row("thief@example.com")).toBeNull();
    // The ticket was not spent on the failed attempt.
    expect((await tryRegister(thief, ticket)).answer).toEqual({ t: "error", code: "pending" });
  });

  it("lets an account from before member-only mode in with a ticket at login", async () => {
    const user = await member("old@example.com", "oldtimer");
    await env.DB.prepare("DELETE FROM users").run();

    const without = await tryLogin(user);
    expect(without.answer).toEqual({ t: "error", code: "access_required" });
    expect((await without.inbox.closed).code).toBe(CLOSE_ACCESS_REQUIRED);

    const withTicket = await tryLogin(user, await ticketFor(ADMIN, "boss"));
    expect(withTicket.answer).toMatchObject({ t: "ready", fresh: false, email: ADMIN, name: "boss" });
    await hangUp(withTicket.inbox);
  });

  it("moves a member to a newly set up phone, keeping their username and standing", async () => {
    const first = await member("zoya@example.com", "zoya");
    // Later: a second code is not sent within a minute of the first.
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + 5 * 60_000);
    const verified = await post("/v1/auth/verify", { email: "zoya@example.com", code: await codeFor("zoya@example.com") });
    const { ticket, username } = (await verified.json()) as { ticket: string; username: string };
    expect(username).toBe("zoya");

    const second = await makeUser();
    const { inbox, answer } = await tryRegister(second, ticket);
    expect(answer).toMatchObject({ t: "ready", email: "zoya@example.com", name: "zoya" });
    await hangUp(inbox);
    expect(await row("zoya@example.com")).toMatchObject({ maun_id: second.id, status: "approved" });

    const old = await tryLogin(first);
    expect(old.answer).toEqual({ t: "error", code: "access_required" });
    await old.inbox.closed;
  });
});

describe("bans and removals", () => {
  it("shuts a banned member out at login, and does not let the address start over", async () => {
    const user = await member("rohan@example.com", "rohan");
    await set("rohan@example.com", "is_banned = 1");
    const { inbox, answer } = await tryLogin(user);
    expect(answer).toEqual({ t: "error", code: "banned" });
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);

    // No code is sent to a banned address, so there is no ticket to come back with. Even with one:
    await env.DB.prepare("INSERT INTO tickets (hash, email, username, expires_at) VALUES (?, 'rohan@example.com', NULL, ?)")
      .bind(await sha256Hex("ticket:forged-for-the-test"), Date.now() + 60_000)
      .run();
    const fresh = await makeUser();
    const retry = await tryRegister(fresh, "forged-for-the-test");
    expect(retry.answer).toEqual({ t: "error", code: "banned" });
    expect((await retry.inbox.closed).code).toBe(CLOSE_BANNED);
  });

  it("closes a connected member's phone soon after a ban", async () => {
    const user = await member("imran@example.com", "imran");
    const { inbox, answer } = await tryLogin(user);
    expect(answer).toMatchObject({ t: "ready" });

    await set("imran@example.com", "is_banned = 1");
    // Within the minute the last answer is still trusted.
    inbox.sendJson(keysFrame());
    expect(await inbox.nextJson()).toMatchObject({ t: "saved" });

    vi.spyOn(Date, "now").mockReturnValue(Date.now() + MEMBER_CHECK_MS + 1000);
    inbox.sendJson(keysFrame());
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "banned" });
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
  });

  it("closes the phone of a member who was removed, and asks them to log in again", async () => {
    const user = await member("imran@example.com", "imran");
    const { inbox } = await tryLogin(user);
    await env.DB.prepare("DELETE FROM users WHERE email = 'imran@example.com'").run();
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + MEMBER_CHECK_MS + 1000);
    inbox.sendJson(keysFrame());
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "access_required" });
    expect((await inbox.closed).code).toBe(CLOSE_ACCESS_REQUIRED);
  });

  it("answers a banned member's file requests with 403", async () => {
    const user = await member("meera@example.com", "meera");
    const { inbox, answer } = await tryLogin(user);
    const headers = { Authorization: `Bearer ${user.id}.${answer.files as string}`, "CF-Connecting-IP": freshIp() };
    const ok = await SELF.fetch("https://relay.test/v1/files", { method: "POST", headers, body: randomBytes(50) });
    expect(ok.status).toBe(201);
    const { key } = (await ok.json()) as { key: string };

    await set("meera@example.com", "is_banned = 1");
    const upload = await SELF.fetch("https://relay.test/v1/files", { method: "POST", headers, body: randomBytes(50) });
    expect(upload.status).toBe(403);
    expect(await upload.json()).toEqual({ error: "banned" });
    expect((await SELF.fetch(`https://relay.test/v1/files/${key}`, { headers })).status).toBe(403);
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
  });
});

describe("usernames", () => {
  it("finds an approved member by username or by ID, and nobody else", async () => {
    const asha = await member("asha@example.com", "asha");
    const rohan = await member("rohan@example.com", "rohan");
    const waiting = await applicant("zoya@example.com", "zoya");
    const { inbox } = await tryLogin(asha);
    const ask = async (frame: Record<string, unknown>) => {
      const id = requestId();
      inbox.sendJson({ t: "find", id, ...frame });
      const answer = await inbox.nextJson();
      expect(answer.id).toBe(id);
      return answer;
    };

    expect(await ask({ name: "rohan" })).toMatchObject({ t: "found", user: rohan.id, name: "rohan" });
    expect(await ask({ name: "@Rohan " })).toMatchObject({ t: "found", user: rohan.id });
    expect(await ask({ user: rohan.id })).toMatchObject({ t: "found", user: rohan.id, name: "rohan" });

    expect(await ask({ name: "nobody" })).toMatchObject({ t: "failed", code: "no_such_user" });
    expect(await ask({ name: "zoya" })).toMatchObject({ t: "failed", code: "no_such_user" });
    expect(await ask({ user: waiting.id })).toMatchObject({ t: "failed", code: "no_such_user" });
    await set("rohan@example.com", "is_banned = 1");
    expect(await ask({ name: "rohan" })).toMatchObject({ t: "failed", code: "no_such_user" });

    inbox.sendJson({ t: "find", id: requestId(), name: "x; DROP TABLE users" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });
    inbox.sendJson({ t: "find", id: requestId(), name: "rohan", user: rohan.id });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });
    await hangUp(inbox);
  });

  it("records the phone a member uses, and notices another one", async () => {
    const user = await member("asha@example.com", "asha");
    const { inbox } = await tryLogin(user);
    const first = "11".repeat(32);
    const second = "22".repeat(32);

    inbox.sendJson({ t: "device", id: requestId(), hash: first, maker: "Google", model: "Pixel 8", os: "16" });
    expect(await inbox.nextJson()).toMatchObject({ t: "saved" });
    expect(await row("asha@example.com")).toMatchObject({ device_hash: first, device_model: "Google Pixel 8 (Android 16)", device_changed_at: null });

    inbox.sendJson({ t: "device", id: requestId(), hash: second, maker: "Samsung", model: "SM-A546", os: "15" });
    await inbox.nextJson();
    const changed = await row("asha@example.com");
    expect(changed).toMatchObject({ device_hash: second, device_model: "Samsung SM-A546 (Android 15)" });
    expect(changed?.device_changed_at).toBeGreaterThan(0);

    inbox.sendJson({ t: "device", id: requestId(), hash: "nope", maker: "x", model: "y", os: "z" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });
    await hangUp(inbox);
  });
});

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

describe("the admin page", () => {
  /** Logs the administrator in and returns the cookie to send from then on. */
  async function adminCookie(): Promise<string> {
    const response = await post("/v1/admin/login", { email: ADMIN, code: await codeFor(ADMIN) });
    expect(response.status).toBe(200);
    const cookie = response.headers.get("Set-Cookie")!;
    expect(cookie).toMatch(/HttpOnly/);
    expect(cookie).toMatch(/Secure/);
    expect(cookie).toMatch(/SameSite=Strict/);
    return cookie.split(";")[0]!;
  }

  const list = async (cookie: string) => {
    const response = await SELF.fetch("https://relay.test/v1/admin/members", { headers: { Cookie: cookie } });
    expect(response.status).toBe(200);
    return (await response.json()) as { admin: string; members: Record<string, unknown>[]; mailsToday: number };
  };
  const act = (cookie: string, action: string, email: string, headers: Record<string, string> = {}) =>
    post("/v1/admin/members", { action, email }, { Cookie: cookie, ...headers });

  it("is a page that loads nothing from elsewhere", async () => {
    const page = await SELF.fetch("https://relay.test/admin");
    expect(page.status).toBe(200);
    const policy = page.headers.get("Content-Security-Policy")!;
    expect(policy).toContain("default-src 'none'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).not.toContain("script-src 'unsafe-inline'");
    const html = await page.text();
    expect(html).not.toMatch(/src="http|href="http/);
  });

  it("lets nobody in without the administrator's code", async () => {
    expect((await SELF.fetch("https://relay.test/v1/admin/members")).status).toBe(401);
    expect((await SELF.fetch("https://relay.test/v1/admin/members", { headers: { Cookie: `maun_admin=${"A".repeat(43)}` } })).status).toBe(401);
    expect((await act("", "approve", "asha@example.com")).status).toBe(401);

    // Somebody else's address, with a code that really was sent to it.
    const theirs = await post("/v1/admin/login", { email: "asha@example.com", code: await codeFor("asha@example.com") });
    expect(theirs.status).toBe(401);
    // The administrator's address with a wrong code.
    const code = await codeFor(ADMIN);
    const wrong = await post("/v1/admin/login", { email: ADMIN, code: code === "000000" ? "000001" : "000000" });
    expect(wrong.status).toBe(401);
    expect(wrong.headers.get("Set-Cookie")).toBeNull();
  });

  it("shows who is waiting and lets the administrator approve, ban, unban, remove and add", async () => {
    const asha = await applicant("asha@example.com", "asha");
    const cookie = await adminCookie();

    let seen = await list(cookie);
    expect(seen.admin).toBe(ADMIN);
    expect(seen.members).toEqual([expect.objectContaining({ email: "asha@example.com", username: "asha", id: asha.id, status: "pending", banned: false })]);
    expect(seen.mailsToday).toBeGreaterThan(0);

    expect((await act(cookie, "approve", "asha@example.com")).status).toBe(200);
    const joined = await tryRegister(asha);
    expect(joined.answer).toMatchObject({ t: "ready", name: "asha" });
    await hangUp(joined.inbox);

    expect((await act(cookie, "ban", "asha@example.com")).status).toBe(200);
    expect((await tryLogin(asha)).answer).toEqual({ t: "error", code: "banned" });
    expect((await list(cookie)).members[0]).toMatchObject({ banned: true });

    expect((await act(cookie, "unban", "asha@example.com")).status).toBe(200);
    const back = await tryLogin(asha);
    expect(back.answer).toMatchObject({ t: "ready" });
    await hangUp(back.inbox);

    expect((await act(cookie, "remove", "asha@example.com")).status).toBe(200);
    expect((await tryLogin(asha)).answer).toEqual({ t: "error", code: "access_required" });
    expect((await list(cookie)).members).toEqual([]);

    expect((await act(cookie, "add", "Invited@Example.com")).status).toBe(200);
    expect((await list(cookie)).members).toEqual([expect.objectContaining({ email: "invited@example.com", status: "approved", id: null, username: null })]);
    // A ban can be put on an address that has never been seen.
    expect((await act(cookie, "ban", "troll@example.com")).status).toBe(200);
    expect(await row("troll@example.com")).toMatchObject({ is_banned: 1 });
  });

  it("refuses what would lock the administrator out, and requests it cannot read", async () => {
    const cookie = await adminCookie();
    expect((await act(cookie, "ban", ADMIN)).status).toBe(409);
    expect((await act(cookie, "remove", "Boss@Example.com")).status).toBe(409);
    expect((await act(cookie, "promote", "asha@example.com")).status).toBe(400);
    expect((await act(cookie, "ban", "not an address")).status).toBe(400);
  });

  it("does not act on requests sent from another site", async () => {
    await applicant("asha@example.com", "asha");
    const cookie = await adminCookie();
    const foreign = await act(cookie, "approve", "asha@example.com", { Origin: "https://evil.example" });
    expect(foreign.status).toBe(403);
    expect(await row("asha@example.com")).toMatchObject({ status: "pending" });
    const own = await act(cookie, "approve", "asha@example.com", { Origin: "https://relay.test" });
    expect(own.status).toBe(200);
  });

  it("ends the session on logout", async () => {
    const cookie = await adminCookie();
    expect((await post("/v1/admin/logout", {}, { Cookie: cookie })).status).toBe(200);
    expect((await SELF.fetch("https://relay.test/v1/admin/members", { headers: { Cookie: cookie } })).status).toBe(401);
  });
});
