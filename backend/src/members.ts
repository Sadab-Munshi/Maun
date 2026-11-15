// The member list of a member-only relay, kept in the D1 database `DB`.
//
// A relay is member-only when ADMIN_EMAIL and a mail service key are set (see
// mail.ts). Without them nothing here is used and the database stays empty.
//
// How somebody becomes a member:
//   1. They ask for a login code; it is sent to their email address.
//   2. The right code is exchanged for a ticket, good for ten minutes. A new
//      member also picks a username, which is put on the ticket.
//   3. Their phone shows the ticket when it connects. The mailbox checks the
//      phone's signatures first, then redeems the ticket here: the email
//      address is tied to the phone's Maun ID.
//   4. A new member waits until the administrator approves them. The
//      administrator's own address is approved from the start.
// After that the phone logs in with its own key; the list is only asked
// whether the member is still approved and not banned.
//
// users     one row per email address: the ID and username it has, whether
//           it is approved, whether it is banned, and the phone last seen.
// devices   one row per phone a member has used: a hash of what the phone
//           says about itself, and its make and model.
// codes     the login code last sent to an address (hashed), and how many
//           were sent today.
// tickets   proof of a checked email address, until a phone redeems it.
// sessions  the administrator's logins to the admin page.
// mail_days how many emails went out on a day.

import { type MailConfig, mailConfig, sendMail } from "./mail";
import { isUsername } from "./protocol";

/** A code can be tried this many times, and is good for this long. */
export const CODE_ATTEMPTS = 5;
export const CODE_TTL_MS = 10 * 60 * 1000;
/** Codes one address may be sent: one a minute, this many a day. */
export const CODE_GAP_MS = 60 * 1000;
export const CODES_PER_DAY = 5;
/** Emails the relay sends in a day at most. Resend's free plan allows 100. */
export const MAIL_DAILY_CAP = 90;
export const TICKET_TTL_MS = 10 * 60 * 1000;
export const SESSION_TTL_MS = 12 * 60 * 60 * 1000;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
     email TEXT PRIMARY KEY,
     maun_id TEXT UNIQUE,
     username TEXT,
     status TEXT NOT NULL DEFAULT 'pending',
     is_banned INTEGER NOT NULL DEFAULT 0,
     created_at INTEGER NOT NULL DEFAULT 0,
     approved_at INTEGER,
     last_login_at INTEGER,
     device_hash TEXT,
     device_model TEXT,
     device_changed_at INTEGER
   )`,
  `CREATE TABLE IF NOT EXISTS devices (
     maun_id TEXT NOT NULL,
     hash TEXT NOT NULL,
     maker TEXT NOT NULL,
     model TEXT NOT NULL,
     os TEXT NOT NULL,
     first_seen_at INTEGER NOT NULL,
     last_seen_at INTEGER NOT NULL,
     PRIMARY KEY (maun_id, hash)
   )`,
  `CREATE TABLE IF NOT EXISTS codes (
     email TEXT PRIMARY KEY,
     hash TEXT NOT NULL,
     expires_at INTEGER NOT NULL,
     attempts INTEGER NOT NULL,
     sent_at INTEGER NOT NULL,
     day INTEGER NOT NULL,
     count INTEGER NOT NULL
   )`,
  `CREATE TABLE IF NOT EXISTS tickets (
     hash TEXT PRIMARY KEY,
     email TEXT NOT NULL,
     username TEXT,
     expires_at INTEGER NOT NULL
   )`,
  "CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)",
  "CREATE TABLE IF NOT EXISTS mail_days (day INTEGER PRIMARY KEY, sent INTEGER NOT NULL)",
];

let schemaReady: WeakSet<D1Database> = new WeakSet();

async function ready(db: D1Database): Promise<D1Database> {
  if (schemaReady.has(db)) return db;
  await db.batch(SCHEMA.map((statement) => db.prepare(statement)));
  // A list made by an earlier version has no usernames and no approval step.
  // Everyone on it was a member, so they stay approved.
  const columns = (await db.prepare("PRAGMA table_info(users)").all()).results.map((row) => row.name as string);
  if (!columns.includes("username")) await db.prepare("ALTER TABLE users ADD COLUMN username TEXT").run();
  if (!columns.includes("status")) {
    await db.prepare("ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'approved'").run();
  }
  if (!columns.includes("approved_at")) await db.prepare("ALTER TABLE users ADD COLUMN approved_at INTEGER").run();
  await db.prepare("CREATE UNIQUE INDEX IF NOT EXISTS users_username ON users (username)").run();
  schemaReady.add(db);
  return db;
}

/** For tests, which start with an empty database. */
export function forgetSchema(): void {
  schemaReady = new WeakSet();
}

// ---- Settings -------------------------------------------------------------------

export type MemberConfig = { admin: string; mail: MailConfig };

/** The settings that make a relay member-only. Null on an open relay. */
export function memberConfig(env: unknown): MemberConfig | null {
  const admin = normalEmail((env as { ADMIN_EMAIL?: unknown }).ADMIN_EMAIL);
  const mail = mailConfig(env);
  return admin && mail ? { admin, mail } : null;
}

/** An email address in the one form it is stored in, or null when it is not one. */
export function normalEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  if (email.length < 6 || email.length > 254) return null;
  return /^[^\s@<>"',;]+@[^\s@<>"',;]+\.[^\s@<>"',;.]+$/.test(email) ? email : null;
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Six digits, each equally likely. */
function randomCode(): string {
  const limit = 4_294_000_000; // The largest multiple of a million that fits in 32 bits.
  const word = new Uint32Array(1);
  do crypto.getRandomValues(word);
  while (word[0]! >= limit);
  return String(word[0]! % 1_000_000).padStart(6, "0");
}

function sameText(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const dayOf = (now: number) => Math.floor(now / 86_400_000);

// ---- Emails ---------------------------------------------------------------------

/** Sends an email if the day's allowance is not used up, and counts it. */
async function mail(env: Env, config: MemberConfig, to: string, subject: string, text: string, now: number): Promise<boolean> {
  const db = await ready(env.DB);
  const day = dayOf(now);
  const sent = ((await db.prepare("SELECT sent FROM mail_days WHERE day = ?").bind(day).first("sent")) as number | null) ?? 0;
  if (sent >= MAIL_DAILY_CAP) return false;
  // Counted before sending: two requests at once cannot both take the last one.
  await db
    .prepare("INSERT INTO mail_days (day, sent) VALUES (?, 1) ON CONFLICT (day) DO UPDATE SET sent = sent + 1")
    .bind(day)
    .run();
  const status = await sendMail(config.mail, to, subject, text);
  if (status >= 200 && status < 300) return true;
  // Nothing went out, so nothing is used up.
  await db.prepare("UPDATE mail_days SET sent = MAX(sent - 1, 0) WHERE day = ?").bind(day).run();
  return false;
}

export type CodeOutcome = "sent" | "wait" | "limit" | "mail_failed";

/**
 * Sends a login code to [email]. A banned address is answered as if one had
 * been sent, and gets none.
 */
export async function startCode(env: Env, config: MemberConfig, email: string, now: number): Promise<CodeOutcome> {
  const db = await ready(env.DB);
  const banned = await db.prepare("SELECT 1 FROM users WHERE email = ? AND is_banned = 1").bind(email).first();
  if (banned) return "sent";
  const previous = await db.prepare("SELECT sent_at, day, count FROM codes WHERE email = ?").bind(email).first();
  const today = dayOf(now);
  const count = previous && previous.day === today ? (previous.count as number) : 0;
  if (previous && now - (previous.sent_at as number) < CODE_GAP_MS) return "wait";
  if (count >= CODES_PER_DAY) return "limit";

  const code = randomCode();
  const text =
    `Your Maun login code is ${code}\n\n` +
    "It works for ten minutes. If you did not ask for it, ignore this email: nobody can log in without the code.";
  if (!(await mail(env, config, email, `${code} is your Maun login code`, text, now))) return "mail_failed";
  await db
    .prepare(
      `INSERT INTO codes (email, hash, expires_at, attempts, sent_at, day, count) VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6)
       ON CONFLICT (email) DO UPDATE SET hash = excluded.hash, expires_at = excluded.expires_at, attempts = 0,
         sent_at = excluded.sent_at, day = excluded.day, count = excluded.count`,
    )
    .bind(email, await sha256(`code:${email}:${code}`), now + CODE_TTL_MS, now, today, count + 1)
    .run();
  return "sent";
}

/** Checks a login code. A right code works once; a wrong one uses up an attempt. */
export async function checkCode(env: Env, email: string, code: string, now: number): Promise<"ok" | "wrong" | "expired"> {
  const db = await ready(env.DB);
  const row = await db.prepare("SELECT hash, expires_at, attempts FROM codes WHERE email = ?").bind(email).first();
  if (!row || (row.expires_at as number) <= now || (row.attempts as number) >= CODE_ATTEMPTS) return "expired";
  // The attempt is counted before the answer is known, so trying many at once gains nothing.
  const counted = await db
    .prepare("UPDATE codes SET attempts = attempts + 1 WHERE email = ? AND attempts < ?")
    .bind(email, CODE_ATTEMPTS)
    .run();
  if (counted.meta.changes === 0) return "expired";
  if (!sameText(row.hash as string, await sha256(`code:${email}:${code}`))) return "wrong";
  // The code is spent, but the day's count of codes sent stays.
  await db.prepare("UPDATE codes SET expires_at = 0 WHERE email = ?").bind(email).run();
  return "ok";
}

// ---- Tickets --------------------------------------------------------------------

/** A ticket for a checked email address, and the username that address already has, if any. */
export async function newTicket(env: Env, email: string, now: number): Promise<{ ticket: string; username: string | null }> {
  const db = await ready(env.DB);
  const ticket = randomToken();
  const username = (await db.prepare("SELECT username FROM users WHERE email = ?").bind(email).first("username")) as string | null;
  await db
    .prepare("INSERT INTO tickets (hash, email, username, expires_at) VALUES (?, ?, NULL, ?)")
    .bind(await sha256(`ticket:${ticket}`), email, now + TICKET_TTL_MS)
    .run();
  return { ticket, username };
}

/** Puts the username a new member wants on their ticket, if nobody else has it. */
export async function nameTicket(
  env: Env,
  ticket: string,
  username: string,
  now: number,
): Promise<"ok" | "taken" | "bad_name" | "expired"> {
  if (!isUsername(username)) return "bad_name";
  const db = await ready(env.DB);
  const hash = await sha256(`ticket:${ticket}`);
  const row = await db.prepare("SELECT email, expires_at FROM tickets WHERE hash = ?").bind(hash).first();
  if (!row || (row.expires_at as number) <= now) return "expired";
  const email = row.email as string;
  const held = await db.prepare("SELECT 1 FROM users WHERE username = ? AND email <> ?").bind(username, email).first();
  const wanted = await db
    .prepare("SELECT 1 FROM tickets WHERE username = ? AND email <> ? AND expires_at > ?")
    .bind(username, email, now)
    .first();
  if (held || wanted) return "taken";
  await db.prepare("UPDATE tickets SET username = ? WHERE hash = ?").bind(username, hash).run();
  return "ok";
}

/** Uses a ticket up. Returns whose it was, or null when it is not a live ticket. */
async function redeemTicket(env: Env, ticket: string, now: number): Promise<{ email: string; username: string | null } | null> {
  const db = await ready(env.DB);
  const row = await db
    .prepare("DELETE FROM tickets WHERE hash = ? RETURNING email, username, expires_at")
    .bind(await sha256(`ticket:${ticket}`))
    .first();
  if (!row || (row.expires_at as number) <= now) return null;
  return { email: row.email as string, username: row.username as string | null };
}

// ---- Members --------------------------------------------------------------------

export type Member = { email: string; username: string | null; approved: boolean; banned: boolean };

/** The member an ID is tied to, or null when it is tied to nobody. */
export async function memberOf(env: Env, maunId: string): Promise<Member | null> {
  const db = await ready(env.DB);
  const row = await db.prepare("SELECT email, username, status, is_banned FROM users WHERE maun_id = ?").bind(maunId).first();
  if (!row) return null;
  return {
    email: row.email as string,
    username: row.username as string | null,
    approved: row.status === "approved",
    banned: row.is_banned === 1,
  };
}

export type Admission =
  | { is: "member"; email: string; username: string | null }
  | { is: "pending"; email: string; username: string | null; fresh: boolean }
  | { is: "banned" }
  | { is: "stranger" };

/**
 * Decides whether the phone with this ID may log in. Its signatures have been
 * checked already. An ID the list knows is answered from the list. Any other
 * needs a ticket, which ties the ticket's email address to the ID: an address
 * has one ID, so logging in from a newly set up phone moves it there.
 */
export async function admit(env: Env, config: MemberConfig, maunId: string, ticket: string | null, now: number): Promise<Admission> {
  const known = await memberOf(env, maunId);
  if (known) {
    if (known.banned) return { is: "banned" };
    if (!known.approved) return { is: "pending", email: known.email, username: known.username, fresh: false };
    return { is: "member", email: known.email, username: known.username };
  }
  const proven = ticket ? await redeemTicket(env, ticket, now) : null;
  if (!proven) return { is: "stranger" };

  const db = await ready(env.DB);
  const existing = await db.prepare("SELECT username, status, is_banned FROM users WHERE email = ?").bind(proven.email).first();
  if (existing?.is_banned === 1) return { is: "banned" };
  const username = (existing?.username as string | null) ?? proven.username;
  // Everybody has a username; the app asks for one before it gets this far.
  if (!username) return { is: "stranger" };
  const approved = proven.email === config.admin || existing?.status === "approved";
  try {
    await db
      .prepare(
        `INSERT INTO users (email, maun_id, username, status, created_at, approved_at, last_login_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?5)
         ON CONFLICT (email) DO UPDATE SET maun_id = excluded.maun_id, username = excluded.username,
           status = excluded.status, last_login_at = excluded.last_login_at,
           approved_at = COALESCE(approved_at, excluded.approved_at),
           device_hash = NULL, device_model = NULL, device_changed_at = NULL`,
      )
      .bind(proven.email, maunId, username, approved ? "approved" : "pending", now, approved ? now : null)
      .run();
  } catch {
    // Somebody took the username in the last moment. Log in again and pick another.
    return { is: "stranger" };
  }
  if (approved) return { is: "member", email: proven.email, username };
  return { is: "pending", email: proven.email, username, fresh: !existing };
}

/** Tells the administrator that somebody is waiting. Best effort. */
export async function notifyAdmin(env: Env, config: MemberConfig, email: string, username: string | null, origin: string): Promise<void> {
  const text =
    `${username ?? "Somebody"} (${email}) has asked to join your Maun relay.\n\n` +
    `Approve or refuse them here: ${origin}/admin\n\nUntil you approve them they cannot write to anyone.`;
  await mail(env, config, config.admin, `${username ?? email} asks to join Maun`, text, Date.now()).catch(() => false);
}

/** Looks a member up by username or by ID. Only approved members who are not banned are found. */
export async function findMember(env: Env, by: { name: string } | { user: string }): Promise<{ user: string; name: string } | null> {
  const db = await ready(env.DB);
  const where = "name" in by ? "username = ?" : "maun_id = ?";
  const row = await db
    .prepare(`SELECT maun_id, username FROM users WHERE ${where} AND status = 'approved' AND is_banned = 0 AND maun_id IS NOT NULL`)
    .bind("name" in by ? by.name : by.user)
    .first();
  if (!row || !row.username) return null;
  return { user: row.maun_id as string, name: row.username as string };
}

export type DeviceReport = { hash: string; maker: string; model: string; os: string };

/** Records the phone a member is using. `device_changed_at` is set when it is not the one seen before. */
export async function recordDevice(env: Env, maunId: string, device: DeviceReport, now: number): Promise<void> {
  const db = await ready(env.DB);
  await db.batch([
    db
      .prepare(
        `INSERT INTO devices (maun_id, hash, maker, model, os, first_seen_at, last_seen_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)
         ON CONFLICT (maun_id, hash) DO UPDATE SET last_seen_at = excluded.last_seen_at, os = excluded.os`,
      )
      .bind(maunId, device.hash, device.maker, device.model, device.os, now),
    db
      .prepare(
        `UPDATE users SET
           device_changed_at = CASE WHEN device_hash IS NOT NULL AND device_hash <> ?2 THEN ?4 ELSE device_changed_at END,
           device_hash = ?2, device_model = ?3, last_login_at = ?4
         WHERE maun_id = ?1`,
      )
      .bind(maunId, device.hash, `${device.maker} ${device.model} (Android ${device.os})`.trim(), now),
  ]);
}

// ---- The administrator ------------------------------------------------------------

export async function newSession(env: Env, now: number): Promise<string> {
  const db = await ready(env.DB);
  const token = randomToken();
  await db.prepare("INSERT INTO sessions (hash, expires_at) VALUES (?, ?)").bind(await sha256(`session:${token}`), now + SESSION_TTL_MS).run();
  return token;
}

export async function sessionValid(env: Env, token: string, now: number): Promise<boolean> {
  const db = await ready(env.DB);
  const expires = await db.prepare("SELECT expires_at FROM sessions WHERE hash = ?").bind(await sha256(`session:${token}`)).first("expires_at");
  return typeof expires === "number" && expires > now;
}

export async function endSession(env: Env, token: string): Promise<void> {
  const db = await ready(env.DB);
  await db.prepare("DELETE FROM sessions WHERE hash = ?").bind(await sha256(`session:${token}`)).run();
}

export type MemberRow = {
  email: string;
  username: string | null;
  id: string | null;
  status: "pending" | "approved";
  banned: boolean;
  admin: boolean;
  createdAt: number;
  lastLoginAt: number | null;
  device: string | null;
  deviceChangedAt: number | null;
  devices: number;
};

export async function listMembers(env: Env, config: MemberConfig, now: number): Promise<{ members: MemberRow[]; mailsToday: number; mailCap: number }> {
  const db = await ready(env.DB);
  const rows = await db
    .prepare(
      `SELECT u.*, (SELECT COUNT(*) FROM devices d WHERE d.maun_id = u.maun_id) AS devices
       FROM users u ORDER BY (u.status = 'pending') DESC, u.created_at DESC LIMIT 1000`,
    )
    .all();
  const members = rows.results.map((row) => ({
    email: row.email as string,
    username: row.username as string | null,
    id: row.maun_id as string | null,
    status: row.status === "approved" ? ("approved" as const) : ("pending" as const),
    banned: row.is_banned === 1,
    admin: row.email === config.admin,
    createdAt: row.created_at as number,
    lastLoginAt: row.last_login_at as number | null,
    device: row.device_model as string | null,
    deviceChangedAt: row.device_changed_at as number | null,
    devices: row.devices as number,
  }));
  const mailsToday = ((await db.prepare("SELECT sent FROM mail_days WHERE day = ?").bind(dayOf(now)).first("sent")) as number | null) ?? 0;
  return { members, mailsToday, mailCap: MAIL_DAILY_CAP };
}

export type AdminAction = "approve" | "ban" | "unban" | "remove" | "add";

/**
 * approve  lets a waiting member in.
 * ban      shuts an address out, also one that has not joined yet.
 * unban    lifts that.
 * remove   forgets the address: its phone has to log in and be approved again.
 * add      approves an address in advance, so its owner is let straight in.
 */
export async function adminAction(env: Env, config: MemberConfig, action: AdminAction, email: string, now: number): Promise<"done" | "refused"> {
  const db = await ready(env.DB);
  // The administrator cannot lock themselves out.
  if (email === config.admin && (action === "ban" || action === "remove")) return "refused";
  switch (action) {
    case "approve":
      await db.prepare("UPDATE users SET status = 'approved', approved_at = ? WHERE email = ?").bind(now, email).run();
      break;
    case "add":
      await db
        .prepare(
          `INSERT INTO users (email, status, created_at, approved_at) VALUES (?1, 'approved', ?2, ?2)
           ON CONFLICT (email) DO UPDATE SET status = 'approved', approved_at = COALESCE(approved_at, excluded.approved_at)`,
        )
        .bind(email, now)
        .run();
      break;
    case "ban":
      await db
        .prepare(
          `INSERT INTO users (email, is_banned, created_at) VALUES (?1, 1, ?2)
           ON CONFLICT (email) DO UPDATE SET is_banned = 1`,
        )
        .bind(email, now)
        .run();
      break;
    case "unban":
      await db.prepare("UPDATE users SET is_banned = 0 WHERE email = ?").bind(email).run();
      break;
    case "remove": {
      const id = await db.prepare("SELECT maun_id FROM users WHERE email = ?").bind(email).first("maun_id");
      await db.batch([
        db.prepare("DELETE FROM devices WHERE maun_id = ?").bind(id ?? ""),
        db.prepare("DELETE FROM users WHERE email = ?").bind(email),
        db.prepare("DELETE FROM codes WHERE email = ?").bind(email),
        db.prepare("DELETE FROM tickets WHERE email = ?").bind(email),
      ]);
      break;
    }
  }
  return "done";
}

/** Hourly: forgets codes, tickets and sessions that have run out, and old mail counts. */
export async function sweepMembers(env: Env, now: number): Promise<void> {
  const db = await ready(env.DB);
  await db.batch([
    db.prepare("DELETE FROM codes WHERE day < ?").bind(dayOf(now)),
    db.prepare("DELETE FROM tickets WHERE expires_at <= ?").bind(now),
    db.prepare("DELETE FROM sessions WHERE expires_at <= ?").bind(now),
    db.prepare("DELETE FROM mail_days WHERE day < ?").bind(dayOf(now) - 7),
  ]);
}
