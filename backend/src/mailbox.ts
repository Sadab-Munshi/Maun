import { DurableObject } from "cloudflare:workers";
import { verifyDeviceSignature, verifyIdentitySignature } from "./crypto/signatures";
import {
  CLOSE_AUTH_FAILED,
  CLOSE_AUTH_TIMEOUT,
  CLOSE_REPLACED,
  NONCE_BYTES,
  PROTOCOL_VERSION,
  fromBase64,
  fromHex,
  isMailboxId,
  loginTranscript,
  parseClientFrame,
  registerTranscript,
  toBase64,
  type ErrorCode,
  type ServerFrame,
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
  | { s: "ready"; m: string };

type Account = { deviceKey: Uint8Array };

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
 */
export class Mailbox extends DurableObject<Env> {
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
      setState(ws, { s: "ready", m: state.m });
      send(ws, { t: "ready", fresh });
      return;
    }

    if (state.s !== "ready") return reject(ws, "not_authenticated");

    // Round-trip check used by the app's connection test.
    send(ws, { t: "echo", id: frame.id, body: frame.body, at: Date.now() });
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    // 1005 and 1006 are reserved and cannot be sent back.
    closeQuietly(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
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
      deviceKey.buffer.slice(deviceKey.byteOffset, deviceKey.byteOffset + deviceKey.byteLength),
      nonce.buffer.slice(nonce.byteOffset, nonce.byteOffset + nonce.byteLength),
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

function send(ws: WebSocket, frame: ServerFrame): void {
  ws.send(JSON.stringify(frame));
}

function reject(ws: WebSocket, code: ErrorCode): void {
  try {
    send(ws, { t: "error", code });
  } catch {
    // Already closed.
  }
  closeQuietly(ws, CLOSE_AUTH_FAILED, code);
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed by the runtime.
  }
}
