// The member list of a member-only relay, kept in the D1 database `DB`.
//
// A relay is member-only when ACCESS_TEAM and ACCESS_AUD are set. Without
// them nothing here is used and the database stays empty.
//
// users    one row per email address that has logged in (or that an
//          administrator has banned in advance). `maun_id` is the ID that
//          address is tied to. `is_banned = 1` shuts the address out.
// devices  one row per phone a member has used: a hash of what the phone says
//          about itself, and its make and model.
//
// To ban someone, in the D1 console of the Cloudflare dashboard:
//     UPDATE users SET is_banned = 1 WHERE email = 'someone@example.com';

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
     email TEXT PRIMARY KEY,
     maun_id TEXT UNIQUE,
     is_banned INTEGER NOT NULL DEFAULT 0,
     created_at INTEGER NOT NULL DEFAULT 0,
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
];

let schemaReady: WeakSet<D1Database> = new WeakSet();

async function ready(db: D1Database): Promise<D1Database> {
  if (!schemaReady.has(db)) {
    await db.batch(SCHEMA.map((statement) => db.prepare(statement)));
    schemaReady.add(db);
  }
  return db;
}

/** For tests, which start every file with an empty database. */
export function forgetSchema(): void {
  schemaReady = new WeakSet();
}

export type Member = { email: string; banned: boolean };

/** The member an ID is tied to, or null when it is tied to nobody. */
export async function memberOf(env: Env, maunId: string): Promise<Member | null> {
  const db = await ready(env.DB);
  const row = await db.prepare("SELECT email, is_banned FROM users WHERE maun_id = ?").bind(maunId).first();
  return row ? { email: row.email as string, banned: row.is_banned === 1 } : null;
}

/**
 * Ties an email address to an ID. An address has one ID: logging in from a
 * newly set up phone moves the address to the new ID, and the old one is a
 * stranger again. Returns "banned" when the address is shut out.
 */
export async function bind(env: Env, email: string, maunId: string, now: number): Promise<"bound" | "banned"> {
  const db = await ready(env.DB);
  const existing = await db.prepare("SELECT is_banned FROM users WHERE email = ?").bind(email).first();
  if (existing?.is_banned === 1) return "banned";
  await db
    .prepare(
      `INSERT INTO users (email, maun_id, created_at, last_login_at) VALUES (?1, ?2, ?3, ?3)
       ON CONFLICT (email) DO UPDATE SET maun_id = excluded.maun_id, last_login_at = excluded.last_login_at,
         device_hash = NULL, device_model = NULL, device_changed_at = NULL`,
    )
    .bind(email, maunId, now)
    .run();
  return "bound";
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
      .bind(maunId, device.hash, `${device.maker} ${device.model}`.trim(), now),
  ]);
}
