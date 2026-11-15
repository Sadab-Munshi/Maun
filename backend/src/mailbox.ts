import { DurableObject } from "cloudflare:workers";
import { verifyDeviceSignature, verifyIdentitySignature } from "./crypto/signatures";
import {
  PUSH_AUTH_BYTES,
  type VapidKeys,
  createVapidKeys,
  encryptWebPush,
  importPushKey,
  isPushUrl,
  vapidAuthorization,
} from "./crypto/webpush";
import {
  CLOSE_AUTH_FAILED,
  CLOSE_AUTH_TIMEOUT,
  CLOSE_REPLACED,
  DELIVERY_WINDOW,
  MAX_ONE_TIME_KEYS,
  NONCE_BYTES,
  PROTOCOL_VERSION,
  PUSH_CONTACT,
  PUSH_DAILY_CAP,
  PUSH_PAYLOAD,
  PUSH_REPEAT_MS,
  PUSH_TTL_SECONDS,
  PUSH_UNFINISHED,
  QUEUE_CAP,
  QUEUE_CAP_PER_SENDER,
  QUEUE_TTL_MS,
  fromBase64,
  fromHex,
  isMailboxId,
  loginTranscript,
  parseClientFrame,
  registerTranscript,
  toBase64,
  type ClientFrame,
  type ErrorCode,
  type FailureCode,
  type KeyBundle,
  type ServerFrame,
  type SignedKey,
} from "./protocol";

/** A socket that has not logged in after this long is closed to make room. */
export const AUTH_WINDOW_MS = 30_000;
/** Sockets that may wait at the login step of one mailbox at the same time. */
export const MAX_PENDING_SOCKETS = 3;

/**
 * Per-socket state. It lives in the socket's attachment, not in memory,
 * because the object can be evicted and rebuilt while its sockets stay open.
 */
type SocketState =
  /** Waiting for `register` or `login`. `n` is the nonce to sign, `at` when it was issued. */
  | { s: "pending"; m: string; n: string; at: number }
  /** A signature is being checked; a second attempt on the same socket is refused. */
  | { s: "checking"; m: string }
  /**
   * Logged in. `c` is the highest queue position sent on this socket, `o` how
   * many of those messages are still waiting for their `ack`, and `t` when the
   * login happened.
   */
  | { s: "ready"; m: string; c: number; o: number; t: number };

type ReadyState = Extract<SocketState, { s: "ready" }>;

type Account = { deviceKey: Uint8Array };

/** What another mailbox is told when it hands over a message. */
export type StoreOutcome = "stored" | "no_account" | "mailbox_full";

/**
 * One Mailbox per user, addressed by the user's identity public key.
 *
 * Sockets are accepted with the WebSocket Hibernation API, so an idle
 * connection costs no Durable Object duration. Clients keep the connection
 * alive with WebSocket protocol pings, which the runtime answers without waking
 * this object.
 *
 * An account is one row: the public half of the phone's device key. Nothing is
 * written until a registration is accepted, so probing unknown addresses
 * leaves no data behind.
 *
 * Next to the account the mailbox keeps two things for its owner:
 *   - the public pre-keys other people need to start an encrypted session;
 *   - a queue of encrypted messages. A message is written before anything else
 *     happens to it, sent to the phone when one is connected, and deleted the
 *     moment the phone acknowledges it. Nothing else is kept: no history, no
 *     record of who wrote.
 *
 * A phone that is not always connected may also leave a wake-up address (a
 * Web Push endpoint from the push app it uses). When a message arrives and no
 * phone is connected, the mailbox sends one encrypted, content-free wake-up
 * there. The push service learns that this phone was woken and when; it does
 * not learn by whom or why.
 */
export class Mailbox extends DurableObject<Env> {
  // Mirrors of what is in storage, rebuilt after the object has been evicted.
  // They save reading the queue on every message.
  private tablesReady = false;
  private queueSize: { total: number; bySender: Map<string, number> } | null = null;
  private lastSeq: number | null = null;
  private expiryArmed = false;

  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const mailboxId = new URL(request.url).pathname.split("/").pop() ?? "";
    if (!isMailboxId(mailboxId)) return new Response("bad mailbox id", { status: 400 });

    const registered = this.account() !== null;
    if (!registered) {
      // A connection to an address with no account may become a new account.
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const { success } = await this.env.SIGNUP_LIMIT.limit({ key: ip });
      if (!success) return tooManyRequests();
    }

    this.dropStalePending();

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const nonce = toBase64(crypto.getRandomValues(new Uint8Array(NONCE_BYTES)));
    this.ctx.acceptWebSocket(server);
    setState(server, { s: "pending", m: mailboxId, n: nonce, at: Date.now() });
    send(server, { t: "hello", proto: PROTOCOL_VERSION, nonce, registered });
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const state = getState(ws);
    if (!state) {
      ws.close(1011, "no state");
      return;
    }
    const frame = parseClientFrame(raw);
    if (typeof frame === "string") {
      // Someone who has not logged in gets one try, not a conversation.
      if (state.s === "ready") send(ws, { t: "error", code: frame });
      else reject(ws, frame);
      return;
    }

    if (frame.t === "register" || frame.t === "login") {
      if (state.s === "ready") {
        send(ws, { t: "error", code: "already_authenticated" });
        return;
      }
      if (state.s !== "pending") return; // An attempt is already being checked.
      // The nonce is spent here, before any waiting: one attempt per connection.
      setState(ws, { s: "checking", m: state.m });
      const identity = fromHex(state.m);
      const nonce = fromBase64(state.n, NONCE_BYTES);
      if (!nonce) return reject(ws, "bad_frame");

      const fresh = frame.t === "register";
      const failure = fresh
        ? await this.register(identity, nonce, frame.deviceKey, frame.identitySig, frame.deviceSig)
        : await this.login(identity, nonce, frame.sig);
      if (failure) return reject(ws, failure);

      // One live connection per account: the newest login wins. Sockets that
      // never logged in are dropped too; they cannot belong to this phone.
      for (const other of this.ctx.getWebSockets()) {
        if (other !== ws) closeQuietly(other, CLOSE_REPLACED, "replaced");
      }
      this.ensureTables();
      const sql = this.ctx.storage.sql;
      const vapid = await this.vapidKeys();
      // The phone is here: whatever wake-up was on its way has done its job.
      sql.exec("UPDATE push SET pending = 0 WHERE id = 1 AND pending = 1");
      setState(ws, { s: "ready", m: state.m, c: 0, o: 0, t: Date.now() });
      send(ws, {
        t: "ready",
        fresh,
        keys: this.hasKeys(),
        otks: this.oneTimeKeyCount(),
        push: sql.exec("SELECT 1 FROM push WHERE id = 1").toArray().length > 0,
        pushStatus: (sql.exec("SELECT status FROM push_result WHERE id = 1").toArray()[0]?.status as number) ?? null,
        vapid: toBase64(vapid.publicKey),
        queued: this.size().total,
      });
      // Everything that arrived while the phone was away.
      this.pump(ws);
      return;
    }

    if (state.s !== "ready") return reject(ws, "not_authenticated");
    await this.handle(ws, state, frame);
  }

  /** Frames from a logged-in phone. */
  private async handle(
    ws: WebSocket,
    state: ReadyState,
    frame: Exclude<ClientFrame, { t: "register" | "login" }>,
  ): Promise<void> {
    this.ensureTables();
    const sql = this.ctx.storage.sql;
    switch (frame.t) {
      case "echo":
        // Round-trip check used by the app's connection test.
        send(ws, { t: "echo", id: frame.id, body: frame.body, at: Date.now() });
        return;

      case "put_keys":
        // The signatures are not checked here. Whoever fetches the keys checks
        // them against the owner's ID, which the relay could not forge anyway.
        sql.exec(
          `INSERT OR REPLACE INTO keys (id, reg_id, spk_id, spk, spk_sig, kpk_id, kpk, kpk_sig)
           VALUES (1, ?, ?, ?, ?, ?, ?, ?)`,
          frame.regId,
          frame.spk.id,
          blob(frame.spk.key),
          blob(frame.spk.sig),
          frame.kpk.id,
          blob(frame.kpk.key),
          blob(frame.kpk.sig),
        );
        send(ws, { t: "saved", id: frame.id, otks: this.oneTimeKeyCount() });
        return;

      case "put_otks": {
        let room = MAX_ONE_TIME_KEYS - this.oneTimeKeyCount();
        for (const key of frame.keys) {
          if (room <= 0) break;
          const added = sql.exec("INSERT OR IGNORE INTO otks (id, key) VALUES (?, ?)", key.id, blob(key.key));
          room -= added.rowsWritten > 0 ? 1 : 0;
        }
        send(ws, { t: "saved", id: frame.id, otks: this.oneTimeKeyCount() });
        return;
      }

      case "get_keys": {
        const failure = await this.refuse(state.m, frame.of);
        if (failure) {
          send(ws, { t: "failed", id: frame.id, code: failure });
          return;
        }
        let bundle: KeyBundle | FailureCode;
        try {
          bundle = await this.peer(frame.of).takeKeys();
        } catch {
          bundle = "unavailable";
        }
        if (typeof bundle === "string") {
          send(ws, { t: "failed", id: frame.id, code: bundle });
          return;
        }
        send(ws, {
          t: "keys",
          id: frame.id,
          regId: bundle.regId,
          spk: wireKey(bundle.spk),
          kpk: wireKey(bundle.kpk),
          otk: bundle.otk ? { id: bundle.otk.id, key: toBase64(bundle.otk.key) } : null,
        });
        return;
      }

      case "send": {
        const failure = await this.refuse(state.m, frame.to);
        if (failure) {
          send(ws, { t: "failed", id: frame.id, code: failure });
          return;
        }
        let outcome: StoreOutcome | "unavailable";
        try {
          // The recipient's mailbox writes the message before it answers, so
          // "stored" means it will survive whatever happens to either phone.
          outcome = await this.peer(frame.to).store(state.m, frame.kind, frame.body);
        } catch {
          outcome = "unavailable";
        }
        if (outcome === "stored") send(ws, { t: "stored", id: frame.id });
        else send(ws, { t: "failed", id: frame.id, code: outcome });
        return;
      }

      case "ack": {
        // Only something sent on this connection can be acknowledged on it.
        if (frame.seq > state.c) return;
        const gone = sql.exec("DELETE FROM queue WHERE seq = ? RETURNING sender", frame.seq).toArray()[0];
        if (gone) {
          this.forget(gone.sender as string);
          setState(ws, { ...state, o: Math.max(0, state.o - 1) });
        }
        this.pump(ws);
        return;
      }

      case "put_push": {
        const usable =
          isPushUrl(frame.url) && frame.auth.length === PUSH_AUTH_BYTES && (await importPushKey(frame.key)) !== null;
        if (!usable) {
          send(ws, { t: "failed", id: frame.id, code: "bad_push" });
          return;
        }
        // The daily count survives a change of address, so changing it over
        // and over does not buy more wake-ups.
        sql.exec(
          `INSERT INTO push (id, url, key, auth, last, pending, day, sent) VALUES (1, ?, ?, ?, 0, 0, 0, 0)
           ON CONFLICT (id) DO UPDATE SET url = excluded.url, key = excluded.key, auth = excluded.auth, pending = 0`,
          frame.url,
          blob(frame.key),
          blob(frame.auth),
        );
        // What happened to the old address's wake-ups says nothing about this one.
        sql.exec("DELETE FROM push_result");
        send(ws, { t: "saved", id: frame.id, otks: this.oneTimeKeyCount() });
        return;
      }

      case "drop_push":
        sql.exec("DELETE FROM push WHERE id = 1");
        sql.exec("DELETE FROM push_result");
        send(ws, { t: "saved", id: frame.id, otks: this.oneTimeKeyCount() });
        return;
    }
  }

  // ---- Called by other mailboxes -------------------------------------------
  //
  // These are reachable only through the MAILBOX binding, that is, from this
  // Worker's own code. `from` is therefore always a logged-in sender.

  /** Writes a message for this mailbox's owner and passes it on if they are connected. */
  async store(from: string, kind: number, body: Uint8Array): Promise<StoreOutcome> {
    if (!this.account()) return "no_account";
    this.ensureTables();
    const size = this.size();
    if (size.total >= QUEUE_CAP || (size.bySender.get(from) ?? 0) >= QUEUE_CAP_PER_SENDER) return "mailbox_full";

    const now = Date.now();
    const seq = this.nextSeq(now);
    this.ctx.storage.sql.exec(
      "INSERT INTO queue (seq, sender, kind, body, at) VALUES (?, ?, ?, ?, ?)",
      seq,
      from,
      kind,
      blob(body),
      now,
    );
    size.total += 1;
    size.bySender.set(from, (size.bySender.get(from) ?? 0) + 1);

    if (!this.expiryArmed) {
      if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(now + QUEUE_TTL_MS);
      this.expiryArmed = true;
    }
    for (const ws of this.ctx.getWebSockets()) this.pump(ws);
    if (!this.connected()) this.wake(now);
    return "stored";
  }

  /**
   * Hands out what is needed to start a session with this mailbox's owner.
   * A one-time pre-key is given to one asker only, so it is deleted here.
   */
  async takeKeys(): Promise<KeyBundle | "no_account" | "no_keys"> {
    if (!this.account()) return "no_account";
    this.ensureTables();
    const sql = this.ctx.storage.sql;
    const row = sql.exec("SELECT * FROM keys WHERE id = 1").toArray()[0];
    if (!row) return "no_keys";
    const taken = sql
      .exec("DELETE FROM otks WHERE id = (SELECT MIN(id) FROM otks) RETURNING id, key")
      .toArray()[0];
    return {
      regId: row.reg_id as number,
      spk: { id: row.spk_id as number, key: bytes(row.spk), sig: bytes(row.spk_sig) },
      kpk: { id: row.kpk_id as number, key: bytes(row.kpk), sig: bytes(row.kpk_sig) },
      otk: taken ? { id: taken.id as number, key: bytes(taken.key) } : null,
    };
  }

  // ---- Wake-ups --------------------------------------------------------------

  /** Whether a logged-in phone is connected, not counting `except`. */
  private connected(except?: WebSocket): boolean {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws !== except && getState(ws)?.s === "ready") return true;
    }
    return false;
  }

  /**
   * Sends a wake-up to the phone's push address, if it left one and has not
   * just been sent one. Returns at once; the request runs in the background.
   */
  private wake(now: number): void {
    const sql = this.ctx.storage.sql;
    const row = sql.exec("SELECT url, key, auth, last, pending, day, sent FROM push WHERE id = 1").toArray()[0];
    if (!row) return;
    // One is on its way and the phone has not come yet. Sending more would
    // only tell the push service how many messages are waiting.
    if (row.pending === 1 && now - (row.last as number) < PUSH_REPEAT_MS) return;
    const today = Math.floor(now / 86_400_000);
    const sentToday = row.day === today ? (row.sent as number) : 0;
    if (sentToday >= PUSH_DAILY_CAP) return;
    sql.exec("UPDATE push SET last = ?, pending = 1, day = ?, sent = ? WHERE id = 1", now, today, sentToday + 1);
    sql.exec("INSERT OR REPLACE INTO push_result (id, status) VALUES (1, ?)", PUSH_UNFINISHED);
    this.ctx.waitUntil(this.deliverWake(row.url as string, bytes(row.key), bytes(row.auth)));
  }

  private async deliverWake(url: string, key: Uint8Array, auth: Uint8Array): Promise<void> {
    const sql = this.ctx.storage.sql;
    let status = 0;
    try {
      const body = await encryptWebPush(new TextEncoder().encode(PUSH_PAYLOAD), key, auth);
      const response = await fetch(url, {
        method: "POST",
        headers: {
          // Signed with this mailbox's own key: the push service can tell the
          // wake-up comes from where the phone said it would, and nothing more.
          Authorization: await vapidAuthorization(url, await this.vapidKeys(), PUSH_CONTACT, Date.now()),
          "Content-Encoding": "aes128gcm",
          "Content-Type": "application/octet-stream",
          TTL: String(PUSH_TTL_SECONDS),
          Urgency: "high",
        },
        body,
        // A wake-up address must answer for itself; it may not send the relay elsewhere.
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      status = response.status;
      await response.body?.cancel();
    } catch {
      // The push service could not be reached.
    }
    // Kept for the phone to see at its next login. Without it, a push service
    // that turns the relay away would look exactly like one that works.
    sql.exec("UPDATE push_result SET status = ? WHERE id = 1", status);
    if (status >= 200 && status < 300) return;
    if (status === 404 || status === 410) {
      // The push service says this address no longer exists. The phone leaves
      // a new one the next time it connects.
      sql.exec("DELETE FROM push WHERE id = 1 AND url = ?", url);
    } else {
      // It did not get through, so the next message may try again at once.
      sql.exec("UPDATE push SET pending = 0 WHERE id = 1 AND url = ?", url);
    }
  }

  /**
   * The key pair this mailbox signs its wake-ups with, made the first time it
   * is needed. One per mailbox, so that a push service cannot tell from the
   * key which relay, or which other phones, a phone belongs with.
   */
  private async vapidKeys(): Promise<VapidKeys> {
    const sql = this.ctx.storage.sql;
    const read = () => {
      const row = sql.exec("SELECT pub, jwk FROM vapid WHERE id = 1").toArray()[0];
      return row ? { publicKey: bytes(row.pub), privateJwk: row.jwk as string } : null;
    };
    const existing = read();
    if (existing) return existing;
    const created = await createVapidKeys();
    // Two callers may both have got here; the first to write wins.
    sql.exec("INSERT OR IGNORE INTO vapid (id, pub, jwk) VALUES (1, ?, ?)", blob(created.publicKey), created.privateJwk);
    return read() ?? created;
  }

  /**
   * A phone has gone. Anything that arrived while it was connected and was
   * never acknowledged was probably lost on the way, so the phone is woken to
   * fetch it. Older messages are left alone: it has already been woken for
   * those, and a message it cannot accept must not wake it for ever.
   */
  private left(ws: WebSocket): void {
    const state = getState(ws);
    if (state?.s !== "ready" || this.connected(ws)) return;
    const waiting = this.ctx.storage.sql.exec("SELECT 1 FROM queue WHERE at >= ? LIMIT 1", state.t).toArray();
    if (waiting.length > 0) this.wake(Date.now());
  }

  /** Deletes messages nobody collected in time. */
  override async alarm(): Promise<void> {
    this.expiryArmed = false;
    if (!this.account()) return;
    this.ensureTables();
    const sql = this.ctx.storage.sql;
    sql.exec("DELETE FROM queue WHERE at <= ?", Date.now() - QUEUE_TTL_MS);
    this.queueSize = null;
    const oldest = sql.exec("SELECT MIN(at) AS at FROM queue").toArray()[0]?.at;
    if (typeof oldest === "number") {
      await this.ctx.storage.setAlarm(oldest + QUEUE_TTL_MS);
      this.expiryArmed = true;
    }
  }

  /**
   * Sends waiting messages down a logged-in socket, oldest first, keeping at
   * most DELIVERY_WINDOW unacknowledged so a long backlog arrives in steps.
   */
  private pump(ws: WebSocket): void {
    const state = getState(ws);
    if (state?.s !== "ready") return;
    const sql = this.ctx.storage.sql;
    let outstanding = state.o;
    if (outstanding >= DELIVERY_WINDOW) {
      // The count can drift when sent messages expire. Recount before waiting.
      outstanding = Number(sql.exec("SELECT COUNT(*) AS n FROM queue WHERE seq <= ?", state.c).one().n);
    }
    const room = DELIVERY_WINDOW - outstanding;
    if (room <= 0) return;

    let cursor = state.c;
    let sent = 0;
    const rows = sql.exec("SELECT seq, sender, kind, body, at FROM queue WHERE seq > ? ORDER BY seq LIMIT ?", cursor, room);
    for (const row of rows) {
      const ok = send(ws, {
        t: "msg",
        seq: row.seq as number,
        from: row.sender as string,
        kind: row.kind as number,
        body: toBase64(bytes(row.body)),
        at: row.at as number,
      });
      if (!ok) break;
      cursor = row.seq as number;
      sent += 1;
    }
    if (sent > 0 || outstanding !== state.o) setState(ws, { ...state, c: cursor, o: outstanding + sent });
  }

  /** Reasons to turn a request about another mailbox away before bothering it. */
  private async refuse(self: string, other: string): Promise<FailureCode | null> {
    if (other === self) return "self";
    const { success } = await this.env.SEND_LIMIT.limit({ key: self });
    return success ? null : "rate_limited";
  }

  private peer(mailboxId: string): DurableObjectStub<Mailbox> {
    return this.env.MAILBOX.get(this.env.MAILBOX.idFromName(mailboxId)) as DurableObjectStub<Mailbox>;
  }

  private ensureTables(): void {
    if (this.tablesReady) return;
    const sql = this.ctx.storage.sql;
    // One row: the signed pre-key and the Kyber pre-key, with their signatures.
    sql.exec(
      `CREATE TABLE IF NOT EXISTS keys (
         id INTEGER PRIMARY KEY CHECK (id = 1),
         reg_id INTEGER NOT NULL,
         spk_id INTEGER NOT NULL, spk BLOB NOT NULL, spk_sig BLOB NOT NULL,
         kpk_id INTEGER NOT NULL, kpk BLOB NOT NULL, kpk_sig BLOB NOT NULL
       )`,
    );
    sql.exec("CREATE TABLE IF NOT EXISTS otks (id INTEGER PRIMARY KEY, key BLOB NOT NULL)");
    // seq is the only index, to keep each message at one row written and one
    // deleted. `at` is when it arrived, used only to expire it.
    // One row: where to send a wake-up, and the keys to encrypt it with.
    // `last` is when one was last sent, `pending` whether the phone has yet
    // to answer it, `day` and `sent` count the day's wake-ups.
    sql.exec(
      `CREATE TABLE IF NOT EXISTS push (
         id INTEGER PRIMARY KEY CHECK (id = 1),
         url TEXT NOT NULL, key BLOB NOT NULL, auth BLOB NOT NULL,
         last INTEGER NOT NULL, pending INTEGER NOT NULL,
         day INTEGER NOT NULL, sent INTEGER NOT NULL
       )`,
    );
    // One row: the key pair wake-ups are signed with.
    sql.exec("CREATE TABLE IF NOT EXISTS vapid (id INTEGER PRIMARY KEY CHECK (id = 1), pub BLOB NOT NULL, jwk TEXT NOT NULL)");
    // One row: what the push service answered to the last wake-up. An HTTP
    // status, 0 when it could not be reached, PUSH_UNFINISHED while in flight.
    sql.exec("CREATE TABLE IF NOT EXISTS push_result (id INTEGER PRIMARY KEY CHECK (id = 1), status INTEGER NOT NULL)");
    sql.exec(
      `CREATE TABLE IF NOT EXISTS queue (
         seq INTEGER PRIMARY KEY,
         sender TEXT NOT NULL,
         kind INTEGER NOT NULL,
         body BLOB NOT NULL,
         at INTEGER NOT NULL
       )`,
    );
    this.tablesReady = true;
  }

  private hasKeys(): boolean {
    return this.ctx.storage.sql.exec("SELECT 1 FROM keys WHERE id = 1").toArray().length > 0;
  }

  private oneTimeKeyCount(): number {
    return Number(this.ctx.storage.sql.exec("SELECT COUNT(*) AS n FROM otks").one().n);
  }

  private size(): { total: number; bySender: Map<string, number> } {
    if (!this.queueSize) {
      const bySender = new Map<string, number>();
      let total = 0;
      for (const row of this.ctx.storage.sql.exec("SELECT sender, COUNT(*) AS n FROM queue GROUP BY sender")) {
        bySender.set(row.sender as string, Number(row.n));
        total += Number(row.n);
      }
      this.queueSize = { total, bySender };
    }
    return this.queueSize;
  }

  private forget(sender: string): void {
    const size = this.queueSize;
    if (!size) return;
    size.total = Math.max(0, size.total - 1);
    const left = (size.bySender.get(sender) ?? 1) - 1;
    if (left > 0) size.bySender.set(sender, left);
    else size.bySender.delete(sender);
  }

  /**
   * Queue positions only ever grow, even after the queue has been emptied, so
   * a position a phone has seen is never given to a different message.
   */
  private nextSeq(now: number): number {
    if (this.lastSeq === null) {
      let last = Number(this.ctx.storage.sql.exec("SELECT COALESCE(MAX(seq), 0) AS n FROM queue").one().n);
      for (const ws of this.ctx.getWebSockets()) {
        const state = getState(ws);
        if (state?.s === "ready") last = Math.max(last, state.c);
      }
      this.lastSeq = last;
    }
    this.lastSeq = Math.max(now, this.lastSeq + 1);
    return this.lastSeq;
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    this.left(ws);
    // 1005 and 1006 are reserved and cannot be sent back.
    closeQuietly(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    this.left(ws);
    closeQuietly(ws, 1011, "error");
  }

  /** Returns an error code, or null when the account was created. */
  private async register(
    identity: Uint8Array,
    nonce: Uint8Array,
    deviceKey: Uint8Array,
    identitySig: Uint8Array,
    deviceSig: Uint8Array,
  ): Promise<ErrorCode | null> {
    if (this.account()) return "already_registered";
    const transcript = registerTranscript(identity, nonce, deviceKey);
    // The identity key proves the address belongs to the sender; the device key
    // proves the sender holds the key that will be used to log in.
    const identityOk = await verifyIdentitySignature(identity, transcript, identitySig);
    const deviceOk = identityOk && (await verifyDeviceSignature(deviceKey, transcript, deviceSig));
    if (!deviceOk) return "bad_signature";

    // Other frames may have been handled while the signatures were checked.
    // From here to the insert nothing waits, so the check and the write cannot
    // be separated by another registration.
    if (this.account()) return "already_registered";
    const sql = this.ctx.storage.sql;
    sql.exec(
      `CREATE TABLE IF NOT EXISTS account (
         id INTEGER PRIMARY KEY CHECK (id = 1),
         device_key BLOB NOT NULL,
         reg_nonce BLOB NOT NULL,
         created_day INTEGER NOT NULL
       )`,
    );
    // reg_nonce is the challenge the phone baked into its device key, kept so
    // the key can be attested later. created_day is a day number, not a time.
    sql.exec(
      "INSERT INTO account (id, device_key, reg_nonce, created_day) VALUES (1, ?, ?, ?)",
      blob(deviceKey),
      blob(nonce),
      Math.floor(Date.now() / 86_400_000),
    );
    return null;
  }

  /** Returns an error code, or null when the signature matches the account. */
  private async login(identity: Uint8Array, nonce: Uint8Array, signature: Uint8Array): Promise<ErrorCode | null> {
    const account = this.account();
    if (!account) return "not_registered";
    const ok = await verifyDeviceSignature(account.deviceKey, loginTranscript(identity, nonce), signature);
    return ok ? null : "bad_signature";
  }

  private account(): Account | null {
    const sql = this.ctx.storage.sql;
    // Reading must not create anything: an address nobody registered stays empty.
    const table = sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'account'").toArray();
    if (table.length === 0) return null;
    const row = sql.exec("SELECT device_key FROM account WHERE id = 1").toArray()[0];
    if (!row) return null;
    return { deviceKey: new Uint8Array(row.device_key as ArrayBuffer) };
  }

  /**
   * Keeps sockets that never log in from piling up: closes the ones past the
   * login window, then the oldest until there is room for one more.
   */
  private dropStalePending(): void {
    const now = Date.now();
    const pending: { ws: WebSocket; at: number }[] = [];
    for (const ws of this.ctx.getWebSockets()) {
      const state = getState(ws);
      if (state?.s !== "pending") continue;
      if (now - state.at > AUTH_WINDOW_MS) closeQuietly(ws, CLOSE_AUTH_TIMEOUT, "login timeout");
      else pending.push({ ws, at: state.at });
    }
    pending.sort((a, b) => a.at - b.at);
    while (pending.length >= MAX_PENDING_SOCKETS) {
      closeQuietly(pending.shift()!.ws, CLOSE_AUTH_TIMEOUT, "too many waiting");
    }
  }
}

export function tooManyRequests(): Response {
  return new Response("too many requests", { status: 429, headers: { "Retry-After": "60" } });
}

function getState(ws: WebSocket): SocketState | null {
  return (ws.deserializeAttachment() as SocketState | null) ?? null;
}

function setState(ws: WebSocket, state: SocketState): void {
  ws.serializeAttachment(state);
}

/** Returns false when the socket has gone away. */
function send(ws: WebSocket, frame: ServerFrame): boolean {
  try {
    ws.send(JSON.stringify(frame));
    return true;
  } catch {
    return false;
  }
}

function reject(ws: WebSocket, code: ErrorCode): void {
  send(ws, { t: "error", code });
  closeQuietly(ws, CLOSE_AUTH_FAILED, code);
}

/** A byte array in the form SQLite storage accepts. */
function blob(data: Uint8Array): ArrayBuffer {
  return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer;
}

function bytes(value: unknown): Uint8Array {
  return new Uint8Array(value as ArrayBuffer);
}

function wireKey(key: SignedKey): { id: number; key: string; sig: string } {
  return { id: key.id, key: toBase64(key.key), sig: toBase64(key.sig) };
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed by the runtime.
  }
}
