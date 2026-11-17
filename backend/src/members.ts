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
import { type DeviceReport, MAX_RECOVERY_BYTES, isUsername } from "./protocol";

export type { DeviceReport };

/** A code can be tried this many times, and is good for this long. */
export const CODE_ATTEMPTS = 5;
export const CODE_TTL_MS = 10 * 60 * 1000;
/** Codes one address may be sent: one a minute, this many a day. */
export const CODE_GAP_MS = 60 * 1000;
export const CODES_PER_DAY = 4;
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
     verified INTEGER NOT NULL DEFAULT 0,
     created_at INTEGER NOT NULL DEFAULT 0,
     approved_at INTEGER,
     last_login_at INTEGER,
     device_hash TEXT,
     device_model TEXT,
     device_changed_at INTEGER,
     device_id TEXT,
     app_version TEXT,
     logins INTEGER NOT NULL DEFAULT 0,
     reinstalls INTEGER NOT NULL DEFAULT 0
   )`,
  `CREATE TABLE IF NOT EXISTS devices (
     maun_id TEXT NOT NULL,
     hash TEXT NOT NULL,
     maker TEXT NOT NULL,
     model TEXT NOT NULL,
     os TEXT NOT NULL,
     first_seen_at INTEGER NOT NULL,
     last_seen_at INTEGER NOT NULL,
     device_id TEXT,
     PRIMARY KEY (maun_id, hash)
   )`,
  `CREATE TABLE IF NOT EXISTS banned_devices (
     device_id TEXT PRIMARY KEY,
     model TEXT,
     email TEXT,
     banned_at INTEGER NOT NULL
   )`,
  "CREATE TABLE IF NOT EXISTS admin_tries (id INTEGER PRIMARY KEY CHECK (id = 1), wrong INTEGER NOT NULL, locked_until INTEGER NOT NULL)",
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
     expires_at INTEGER NOT NULL,
     replace INTEGER NOT NULL DEFAULT 0
   )`,
  // A member's sealed identity backup: ciphertext and the recipe for opening
  // it, nothing else. The relay cannot read it and has no key for it.
  `CREATE TABLE IF NOT EXISTS recovery (
     maun_id TEXT PRIMARY KEY,
     salt TEXT NOT NULL,
     kdf TEXT NOT NULL,
     blob TEXT NOT NULL,
     updated_at INTEGER NOT NULL
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
  // The administrator's badge: an account official Maun has looked at. Nothing
  // a phone can set, and nothing to do with the safety number it checks itself.
  if (!columns.includes("verified")) {
    await db.prepare("ALTER TABLE users ADD COLUMN verified INTEGER NOT NULL DEFAULT 0").run();
  }
  if (!columns.includes("device_id")) {
    await db.batch([
      db.prepare("ALTER TABLE users ADD COLUMN device_id TEXT"),
      db.prepare("ALTER TABLE users ADD COLUMN app_version TEXT"),
      db.prepare("ALTER TABLE users ADD COLUMN logins INTEGER NOT NULL DEFAULT 0"),
      db.prepare("ALTER TABLE users ADD COLUMN reinstalls INTEGER NOT NULL DEFAULT 0"),
      db.prepare("ALTER TABLE devices ADD COLUMN device_id TEXT"),
    ]);
  }
  const ticketColumns = (await db.prepare("PRAGMA table_info(tickets)").all()).results.map((row) => row.name as string);
  if (!ticketColumns.includes("replace")) {
    await db.prepare("ALTER TABLE tickets ADD COLUMN replace INTEGER NOT NULL DEFAULT 0").run();
  }
  await db.prepare("CREATE UNIQUE INDEX IF NOT EXISTS users_username ON users (username)").run();
  schemaReady.add(db);
  return db;
}

/** For tests, which start with an empty database. */
export function forgetSchema(): void {
  schemaReady = new WeakSet();
}

// ---- Settings -------------------------------------------------------------------

/**
 * `adminPassword`: what the administrator types to get into the admin page,
 * before the code sent by email; null when none (of ten characters or more)
 * is set, and then the page lets nobody in. `adminHost`: the one host name
 * the admin page answers on; null for the relay's own address, under /admin.
 */
export type MemberConfig = { admin: string; mail: MailConfig; adminPassword: string | null; adminHost: string | null };

export const ADMIN_PASSWORD_MIN = 10;

/** The settings that make a relay member-only. Null on an open relay. */
export function memberConfig(env: unknown): MemberConfig | null {
  const vars = env as { ADMIN_EMAIL?: unknown; ADMIN_PASSWORD?: unknown; ADMIN_HOST?: unknown };
  const admin = normalEmail(vars.ADMIN_EMAIL);
  const mail = mailConfig(env);
  if (!admin || !mail) return null;
  const password = typeof vars.ADMIN_PASSWORD === "string" && vars.ADMIN_PASSWORD.length >= ADMIN_PASSWORD_MIN ? vars.ADMIN_PASSWORD : null;
  const host = typeof vars.ADMIN_HOST === "string" ? vars.ADMIN_HOST.trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/.*$/, "") : "";
  return { admin, mail, adminPassword: password, adminHost: host === "" ? null : host };
}

/** How often the admin password may be wrong before the page is closed for a while. */
export const ADMIN_TRIES = 5;
export const ADMIN_LOCK_MS = 15 * 60 * 1000;

/**
 * Checks the administrator's email address and password. After too many
 * wrong tries nobody gets in for a quarter of an hour, right password or not.
 */
export async function checkAdminPassword(env: Env, config: MemberConfig, email: string | null, password: string, now: number): Promise<"ok" | "wrong" | "locked" | "no_password"> {
  if (!config.adminPassword) return "no_password";
  const db = await ready(env.DB);
  const tries = await db.prepare("SELECT wrong, locked_until FROM admin_tries WHERE id = 1").first();
  if (tries && (tries.locked_until as number) > now) return "locked";
  // Compared as digests, so that how long it takes says nothing about the password.
  const right = email === config.admin && sameText(await sha256(`pw:${password}`), await sha256(`pw:${config.adminPassword}`));
  if (right) {
    await db.prepare("DELETE FROM admin_tries").run();
    return "ok";
  }
  const wrong = ((tries?.wrong as number | undefined) ?? 0) + 1;
  await db
    .prepare("INSERT OR REPLACE INTO admin_tries (id, wrong, locked_until) VALUES (1, ?, ?)")
    .bind(wrong >= ADMIN_TRIES ? 0 : wrong, wrong >= ADMIN_TRIES ? now + ADMIN_LOCK_MS : 0)
    .run();
  return wrong >= ADMIN_TRIES ? "locked" : "wrong";
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

/** What a code is for. One sent for the app does not open the admin page, and the other way round. */
export type CodePurpose = "app" | "admin";
const codeKey = (email: string, purpose: CodePurpose) => (purpose === "admin" ? `admin:${email}` : email);

/**
 * Sends a login code to [email]. A banned address is answered as if one had
 * been sent, and gets none.
 */
export async function startCode(env: Env, config: MemberConfig, email: string, now: number, purpose: CodePurpose = "app"): Promise<CodeOutcome> {
  const db = await ready(env.DB);
  const banned = await db.prepare("SELECT 1 FROM users WHERE email = ? AND is_banned = 1").bind(email).first();
  if (banned && purpose === "app") return "sent";
  const key = codeKey(email, purpose);
  const previous = await db.prepare("SELECT sent_at, day, count FROM codes WHERE email = ?").bind(key).first();
  const today = dayOf(now);
  const count = previous && previous.day === today ? (previous.count as number) : 0;
  if (previous && now - (previous.sent_at as number) < CODE_GAP_MS) return "wait";
  if (count >= CODES_PER_DAY) return "limit";

  const code = randomCode();
  const what = purpose === "admin" ? "Maun admin code" : "Maun login code";
  const text =
    `Your ${what} is ${code}\n\n` +
    "It works for ten minutes. If you did not ask for it, ignore this email: nobody can log in without the code.";
  if (!(await mail(env, config, email, `${code} is your ${what}`, text, now))) return "mail_failed";
  await db
    .prepare(
      `INSERT INTO codes (email, hash, expires_at, attempts, sent_at, day, count) VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6)
       ON CONFLICT (email) DO UPDATE SET hash = excluded.hash, expires_at = excluded.expires_at, attempts = 0,
         sent_at = excluded.sent_at, day = excluded.day, count = excluded.count`,
    )
    .bind(key, await sha256(`code:${key}:${code}`), now + CODE_TTL_MS, now, today, count + 1)
    .run();
  return "sent";
}

/** Checks a login code. A right code works once; a wrong one uses up an attempt. */
export async function checkCode(env: Env, address: string, code: string, now: number, purpose: CodePurpose = "app"): Promise<"ok" | "wrong" | "expired"> {
  const db = await ready(env.DB);
  const email = codeKey(address, purpose);
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

/**
 * A ticket for a checked email address, and what that address already has: its
 * username, the ID it is tied to, and whether that ID left a sealed backup the
 * phone could be recovered from.
 *
 * The ID matters because a ticket is the only thing that can say "this is that
 * member". A phone that turns up with new keys and a ticket for an address
 * that already has an ID is *not* allowed to take that ID's place by logging
 * in: see [admit].
 */
export type Ticket = {
  ticket: string;
  username: string | null;
  id: string | null;
  hasRecovery: boolean;
};

export async function newTicket(env: Env, email: string, now: number): Promise<Ticket> {
  const db = await ready(env.DB);
  const ticket = randomToken();
  const existing = await db.prepare("SELECT maun_id, username FROM users WHERE email = ?").bind(email).first();
  const username = (existing?.username as string | null | undefined) ?? null;
  const id = (existing?.maun_id as string | null | undefined) ?? null;
  await db
    .prepare("INSERT INTO tickets (hash, email, username, expires_at) VALUES (?, ?, NULL, ?)")
    .bind(await sha256(`ticket:${ticket}`), email, now + TICKET_TTL_MS)
    .run();
  return { ticket, username, id, hasRecovery: id !== null && (await hasRecovery(env, id)) };
}

/** The ID a checked email address is tied to, if any. Used by the ticket routes. */
export async function idForTicket(
  env: Env,
  ticket: string,
  now: number,
): Promise<{ email: string; username: string | null; id: string | null; hasRecovery: boolean } | null> {
  const db = await ready(env.DB);
  const row = await db
    .prepare("SELECT email, username, expires_at FROM tickets WHERE hash = ?")
    .bind(await sha256(`ticket:${ticket}`))
    .first();
  if (!row || (row.expires_at as number) <= now) return null;
  const existing = await db.prepare("SELECT maun_id, username FROM users WHERE email = ?").bind(row.email as string).first();
  const id = (existing?.maun_id as string | null | undefined) ?? null;
  return {
    email: row.email as string,
    username: (existing?.username as string | null | undefined) ?? null,
    id,
    hasRecovery: id !== null && (await hasRecovery(env, id)),
  };
}

/**
 * Records that the person behind this ticket has been told what starting a new
 * ID means and has chosen to do it anyway. Only a ticket marked this way may
 * take the place of an ID that already exists.
 */
export async function consentToReplace(env: Env, ticket: string, now: number): Promise<boolean> {
  const db = await ready(env.DB);
  const hash = await sha256(`ticket:${ticket}`);
  const row = await db.prepare("SELECT expires_at FROM tickets WHERE hash = ?").bind(hash).first();
  if (!row || (row.expires_at as number) <= now) return false;
  await db.prepare("UPDATE tickets SET replace = 1 WHERE hash = ?").bind(hash).run();
  return true;
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
async function redeemTicket(
  env: Env,
  ticket: string,
  now: number,
): Promise<{ email: string; username: string | null; replace: boolean } | null> {
  const db = await ready(env.DB);
  const row = await db
    .prepare("DELETE FROM tickets WHERE hash = ? RETURNING email, username, expires_at, replace")
    .bind(await sha256(`ticket:${ticket}`))
    .first();
  if (!row || (row.expires_at as number) <= now) return null;
  return { email: row.email as string, username: row.username as string | null, replace: row.replace === 1 };
}

// ---- Sealed identity backups -----------------------------------------------------
//
// A member may leave a copy of their identity on the relay so that a phone they
// no longer have can be recovered. What is stored is ciphertext: the key comes
// from a passphrase only the member knows, and the relay has no way to open it,
// to check the passphrase, or to make a new one. Everything the relay can do
// with a row here is hand it back to somebody who has proved the email address
// on the same account.

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const KDF = /^[a-z0-9-]{1,32}$/;

export type Recovery = { salt: string; kdf: string; blob: string };

/** Whether this ID left a sealed backup behind. */
export async function hasRecovery(env: Env, maunId: string): Promise<boolean> {
  const db = await ready(env.DB);
  return (await db.prepare("SELECT 1 FROM recovery WHERE maun_id = ?").bind(maunId).first()) !== null;
}

/** The sealed backup of an ID, or null when there is none. Readable only by its owner. */
export async function getRecovery(env: Env, maunId: string): Promise<Recovery | null> {
  const db = await ready(env.DB);
  const row = await db
    .prepare("SELECT salt, kdf, blob FROM recovery WHERE maun_id = ?")
    .bind(maunId)
    .first();
  if (!row) return null;
  return { salt: row.salt as string, kdf: row.kdf as string, blob: row.blob as string };
}

/** Checks the shape of a backup before it is written. Null when it is not one. */
export function checkRecovery(salt: unknown, kdf: unknown, blob: unknown): Recovery | null {
  if (typeof salt !== "string" || !BASE64.test(salt) || salt.length > 128) return null;
  if (typeof kdf !== "string" || !KDF.test(kdf)) return null;
  if (typeof blob !== "string" || !BASE64.test(blob)) return null;
  if (blob.length === 0 || blob.length > Math.ceil(MAX_RECOVERY_BYTES / 3) * 4) return null;
  return { salt, kdf, blob };
}

/** Writes a member's sealed backup over whatever was there before. */
export async function putRecovery(env: Env, maunId: string, recovery: Recovery, now: number): Promise<void> {
  const db = await ready(env.DB);
  await db
    .prepare(
      `INSERT INTO recovery (maun_id, salt, kdf, blob, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)
       ON CONFLICT (maun_id) DO UPDATE SET salt = excluded.salt, kdf = excluded.kdf,
         blob = excluded.blob, updated_at = excluded.updated_at`,
    )
    .bind(maunId, recovery.salt, recovery.kdf, recovery.blob, now)
    .run();
}

/** Forgets a member's backup: they asked for it, or their account went away. */
export async function dropRecovery(env: Env, maunId: string): Promise<void> {
  const db = await ready(env.DB);
  await db.prepare("DELETE FROM recovery WHERE maun_id = ?").bind(maunId).run();
}

// ---- Members --------------------------------------------------------------------

export type Member = {
  email: string;
  username: string | null;
  approved: boolean;
  banned: boolean;
  /** The administrator has verified this account: the badge other phones show. */
  verified: boolean;
};

/** The member an ID is tied to, or null when it is tied to nobody. A member whose phone is banned counts as banned. */
export async function memberOf(env: Env, maunId: string): Promise<Member | null> {
  const db = await ready(env.DB);
  const row = await db
    .prepare(
      `SELECT email, username, status, is_banned, verified,
         EXISTS (SELECT 1 FROM banned_devices b WHERE b.device_id = users.device_id) AS device_banned
       FROM users WHERE maun_id = ?`,
    )
    .bind(maunId)
    .first();
  if (!row) return null;
  return {
    email: row.email as string,
    username: row.username as string | null,
    approved: row.status === "approved",
    banned: row.is_banned === 1 || row.device_banned === 1,
    verified: row.verified === 1,
  };
}

async function deviceBanned(db: D1Database, deviceId: string): Promise<boolean> {
  return (await db.prepare("SELECT 1 FROM banned_devices WHERE device_id = ?").bind(deviceId).first()) !== null;
}

/**
 * `movedFrom`: the ID this member had until now, when this login moved them
 * to a new one. Whoever writes to the old ID has to be told it is gone.
 */
export type Admission =
  | { is: "member"; email: string; username: string | null; movedFrom: string | null; verified: boolean }
  | { is: "pending"; email: string; username: string | null; fresh: boolean; movedFrom: string | null }
  /**
   * This address already has an ID, and this phone is not it. Nothing is
   * changed: the account, its username, its badge, its picture and anything
   * waiting for it stay exactly as they were. The phone is offered the sealed
   * backup (`hasRecovery`), or — if there is none — the choice to start a new
   * ID, which it has to ask for on purpose.
   */
  | { is: "conflict"; email: string; username: string | null; id: string; hasRecovery: boolean }
  | { is: "banned" }
  | { is: "stranger" };

/**
 * Decides whether the phone with this ID may log in. Its signatures have been
 * checked already. A banned phone is turned away whoever uses it. An ID the
 * list knows is answered from the list. Any other needs a ticket, which ties
 * the ticket's email address to the ID: an address has one ID, so logging in
 * from a newly set up phone moves it there.
 */
export async function admit(
  env: Env,
  config: MemberConfig,
  maunId: string,
  ticket: string | null,
  device: DeviceReport | null,
  now: number,
): Promise<Admission> {
  const db = await ready(env.DB);
  if (device?.did && (await deviceBanned(db, device.did))) return { is: "banned" };

  const known = await memberOf(env, maunId);
  if (known) {
    // A phone that does not say which one it is goes by the one last seen: memberOf looked.
    if (known.banned) return { is: "banned" };
    if (device) await recordDevice(env, maunId, device, now);
    if (!known.approved) return { is: "pending", email: known.email, username: known.username, fresh: false, movedFrom: null };
    await db.prepare("UPDATE users SET logins = logins + 1, last_login_at = ? WHERE maun_id = ?").bind(now, maunId).run();
    return { is: "member", email: known.email, username: known.username, movedFrom: null, verified: known.verified };
  }
  const proven = ticket ? await redeemTicket(env, ticket, now) : null;
  if (!proven) return { is: "stranger" };

  const existing = await db.prepare("SELECT maun_id, username, status, is_banned FROM users WHERE email = ?").bind(proven.email).first();
  if (existing?.is_banned === 1) return { is: "banned" };
  // An address has one ID. This phone does not hold that ID's keys, and a login
  // is not a reason to hand the address — its username, its badge, its picture
  // and everything waiting for it — to a new key pair. The member has to either
  // restore the old ID from its sealed backup, or say in as many words that
  // they are starting again.
  const previous = (existing?.maun_id as string | null | undefined) ?? null;
  if (previous !== null && previous !== maunId && !proven.replace) {
    return {
      is: "conflict",
      email: proven.email,
      username: (existing?.username as string | null | undefined) ?? null,
      id: previous,
      hasRecovery: await hasRecovery(env, previous),
    };
  }
  const username = (existing?.username as string | null) ?? proven.username;
  // Everybody has a username; the app asks for one before it gets this far.
  if (!username) return { is: "stranger" };
  const approved = proven.email === config.admin || existing?.status === "approved";
  const movedFrom = (existing?.maun_id as string | null | undefined) ?? null;
  try {
    await db
      .prepare(
        `INSERT INTO users (email, maun_id, username, status, created_at, approved_at, last_login_at, logins)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?5, 1)
         ON CONFLICT (email) DO UPDATE SET maun_id = excluded.maun_id, username = excluded.username,
           status = excluded.status, last_login_at = excluded.last_login_at,
           approved_at = COALESCE(approved_at, excluded.approved_at),
           logins = logins + 1, reinstalls = reinstalls + ?7`,
      )
      .bind(proven.email, maunId, username, approved ? "approved" : "pending", now, approved ? now : null, movedFrom ? 1 : 0)
      .run();
  } catch {
    // Somebody took the username in the last moment. Log in again and pick another.
    return { is: "stranger" };
  }
  if (device) await recordDevice(env, maunId, device, now);
  // The old ID has been replaced, deliberately, by a member who was told what
  // that means. Nothing can be recovered to it any more, so its sealed backup
  // — ciphertext the relay cannot read — goes as well.
  if (movedFrom && movedFrom !== maunId) await dropRecovery(env, movedFrom);
  // A list that was written to before this member joined keeps the badge it was given.
  if (approved) {
    return { is: "member", email: proven.email, username, movedFrom, verified: (await memberOf(env, maunId))?.verified === true };
  }
  return { is: "pending", email: proven.email, username, fresh: !existing, movedFrom };
}

/** Tells the administrator that somebody is waiting. Best effort. */
export async function notifyAdmin(env: Env, config: MemberConfig, email: string, username: string | null, origin: string): Promise<void> {
  const text =
    `${username ?? "Somebody"} (${email}) has asked to join your Maun relay.\n\n` +
    `Approve or refuse them here: ${config.adminHost ? `https://${config.adminHost}/` : `${origin}/admin`}\n\n` +
    "Until you approve them they cannot write to anyone.";
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

/**
 * Records the phone a member is using. `device_changed_at` is set when it is
 * not the one seen before: by its lasting ID where both reports have one, by
 * the hash of everything otherwise.
 */
export async function recordDevice(env: Env, maunId: string, device: DeviceReport, now: number): Promise<void> {
  const db = await ready(env.DB);
  await db.batch([
    db
      .prepare(
        `INSERT INTO devices (maun_id, hash, maker, model, os, first_seen_at, last_seen_at, device_id) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7)
         ON CONFLICT (maun_id, hash) DO UPDATE SET last_seen_at = excluded.last_seen_at, os = excluded.os,
           device_id = COALESCE(excluded.device_id, device_id)`,
      )
      .bind(maunId, device.hash, device.maker, device.model, device.os, now, device.did),
    db
      .prepare(
        `UPDATE users SET
           device_changed_at = CASE
             WHEN device_id IS NOT NULL AND ?5 IS NOT NULL THEN CASE WHEN device_id <> ?5 THEN ?4 ELSE device_changed_at END
             WHEN device_hash IS NOT NULL AND device_hash <> ?2 THEN ?4
             ELSE device_changed_at END,
           device_hash = ?2, device_model = ?3, device_id = COALESCE(?5, device_id), app_version = COALESCE(?6, app_version)
         WHERE maun_id = ?1`,
      )
      .bind(maunId, device.hash, `${device.maker} ${device.model} (Android ${device.os})`.trim(), now, device.did, device.app),
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
  /** The verified badge: granted and taken away by the administrator alone. */
  verified: boolean;
  admin: boolean;
  createdAt: number;
  approvedAt: number | null;
  lastLoginAt: number | null;
  logins: number;
  /** How often this address has moved to a newly set up phone or a fresh install. */
  reinstalls: number;
  device: string | null;
  deviceId: string | null;
  deviceBanned: boolean;
  deviceChangedAt: number | null;
  devices: number;
  appVersion: string | null;
};

export type BannedDevice = { id: string; model: string | null; email: string | null; bannedAt: number };

export async function listMembers(
  env: Env,
  config: MemberConfig,
  now: number,
): Promise<{ members: MemberRow[]; bannedDevices: BannedDevice[]; mailsToday: number; mailCap: number }> {
  const db = await ready(env.DB);
  const rows = await db
    .prepare(
      `SELECT u.*, (SELECT COUNT(*) FROM devices d WHERE d.maun_id = u.maun_id) AS devices,
         EXISTS (SELECT 1 FROM banned_devices b WHERE b.device_id = u.device_id) AS device_banned
       FROM users u ORDER BY (u.status = 'pending') DESC, u.created_at DESC LIMIT 1000`,
    )
    .all();
  const members = rows.results.map((row) => ({
    email: row.email as string,
    username: row.username as string | null,
    id: row.maun_id as string | null,
    status: row.status === "approved" ? ("approved" as const) : ("pending" as const),
    banned: row.is_banned === 1,
    verified: row.verified === 1,
    admin: row.email === config.admin,
    createdAt: row.created_at as number,
    approvedAt: row.approved_at as number | null,
    lastLoginAt: row.last_login_at as number | null,
    logins: (row.logins as number | null) ?? 0,
    reinstalls: (row.reinstalls as number | null) ?? 0,
    device: row.device_model as string | null,
    deviceId: row.device_id as string | null,
    deviceBanned: row.device_banned === 1,
    deviceChangedAt: row.device_changed_at as number | null,
    devices: row.devices as number,
    appVersion: row.app_version as string | null,
  }));
  const banned = await db.prepare("SELECT device_id, model, email, banned_at FROM banned_devices ORDER BY banned_at DESC LIMIT 1000").all();
  const bannedDevices = banned.results.map((row) => ({
    id: row.device_id as string,
    model: row.model as string | null,
    email: row.email as string | null,
    bannedAt: row.banned_at as number,
  }));
  const mailsToday = ((await db.prepare("SELECT sent FROM mail_days WHERE day = ?").bind(dayOf(now)).first("sent")) as number | null) ?? 0;
  return { members, bannedDevices, mailsToday, mailCap: MAIL_DAILY_CAP };
}

export type AdminAction =
  | "approve"
  | "ban"
  | "unban"
  | "remove"
  | "add"
  | "verify"
  | "unverify"
  | "ban_device"
  | "unban_device";

/**
 * approve       lets a waiting member in.
 * ban           shuts an address out, also one that has not joined yet.
 * unban         lifts that.
 * remove        forgets the address: its phone has to log in and be approved again.
 * add           approves an address in advance, so its owner is let straight in.
 * verify        gives the account the verified badge: every phone that has this
 *               member as a contact shows a green tick beside their name.
 * unverify      takes it away again.
 * ban_device    shuts a phone out, whichever address is used on it. [target] is the phone's ID.
 * unban_device  lifts that.
 *
 * For everything but the last two, [target] is an email address.
 */
export async function adminAction(env: Env, config: MemberConfig, action: AdminAction, target: string, now: number): Promise<"done" | "refused"> {
  const db = await ready(env.DB);
  const email = target;
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
    case "verify":
      await db.prepare("UPDATE users SET verified = 1 WHERE email = ?").bind(email).run();
      break;
    case "unverify":
      await db.prepare("UPDATE users SET verified = 0 WHERE email = ?").bind(email).run();
      break;
    case "ban_device": {
      const owners = await db.prepare("SELECT email, device_model FROM users WHERE device_id = ?").bind(target).all();
      if (owners.results.some((row) => row.email === config.admin)) return "refused";
      const last = owners.results[0];
      await db
        .prepare("INSERT OR IGNORE INTO banned_devices (device_id, model, email, banned_at) VALUES (?, ?, ?, ?)")
        .bind(target, (last?.device_model as string | undefined) ?? null, (last?.email as string | undefined) ?? null, now)
        .run();
      break;
    }
    case "unban_device":
      await db.prepare("DELETE FROM banned_devices WHERE device_id = ?").bind(target).run();
      break;
  }
  return "done";
}

/**
 * Which of these accounts wear the verified badge. Asked by a phone about the
 * people it has as contacts; the answer says only yes or no for each ID, never
 * whose account it is, and only an approved, unbanned member can be verified.
 * This is the administrator's badge, not the safety number: it says that the
 * relay's owner has looked at an account, nothing about the person behind it.
 */
export async function verifiedAmong(env: Env, ids: string[]): Promise<Record<string, boolean>> {
  const answer: Record<string, boolean> = {};
  for (const id of ids) answer[id] = false;
  if (ids.length === 0) return answer;
  const db = await ready(env.DB);
  const marks = ids.map(() => "?").join(",");
  const rows = await db
    .prepare(`SELECT maun_id, verified FROM users WHERE maun_id IN (${marks}) AND status = 'approved' AND is_banned = 0`)
    .bind(...ids)
    .all();
  for (const row of rows.results) {
    const id = row.maun_id as string | null;
    if (id) answer[id] = row.verified === 1;
  }
  return answer;
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
