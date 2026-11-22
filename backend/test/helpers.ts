import { SELF } from "cloudflare:test";
import { expect } from "vitest";
import { PROTOCOL_VERSION, fromBase64, fromHex, loginTranscript, registerTranscript, toBase64 } from "../src/protocol";

/** Queues everything a socket receives so tests can await frames in order. */
export class Inbox {
  private queue: string[] = [];
  private waiters: ((value: string) => void)[] = [];
  closed: Promise<{ code: number; reason: string }>;

  constructor(readonly ws: WebSocket) {
    ws.addEventListener("message", (event) => {
      const text = typeof event.data === "string" ? event.data : "<binary>";
      const waiter = this.waiters.shift();
      if (waiter) waiter(text);
      else this.queue.push(text);
    });
    this.closed = new Promise((resolve) => {
      ws.addEventListener("close", (event) => resolve({ code: event.code, reason: event.reason }));
    });
    ws.accept();
  }

  next(): Promise<string> {
    const queued = this.queue.shift();
    if (queued !== undefined) return Promise.resolve(queued);
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  async nextJson(): Promise<Record<string, unknown>> {
    return JSON.parse(await this.next());
  }

  sendJson(frame: unknown): void {
    this.ws.send(JSON.stringify(frame));
  }
}

let ipCounter = 0;

/** A new made-up client address, so one test's traffic never counts against another's limits. */
export function freshIp(): string {
  ipCounter += 1;
  return `198.51.${Math.floor(ipCounter / 250)}.${(ipCounter % 250) + 1}`;
}

export function openSocket(mailboxId: string, ip: string = freshIp()): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/ws/${mailboxId}`, {
    headers: { Upgrade: "websocket", "CF-Connecting-IP": ip },
  });
}

export type Connection = { inbox: Inbox; nonce: Uint8Array; registered: boolean };

/** Opens a socket and reads the `hello` frame. */
export async function connect(mailboxId: string, ip?: string): Promise<Connection> {
  const response = await openSocket(mailboxId, ip);
  expect(response.status).toBe(101);
  const inbox = new Inbox(response.webSocket!);
  const hello = await inbox.nextJson();
  expect(hello).toMatchObject({ t: "hello", proto: PROTOCOL_VERSION });
  const nonce = fromBase64(hello.nonce, 32);
  expect(nonce?.length).toBe(32);
  return { inbox, nonce: nonce!, registered: hello.registered as boolean };
}

// ---- Test keys -------------------------------------------------------------
//
// The relay's own checks are verified against signatures made by libsignal and
// the JDK in signatures.test.ts. Here the keys are made with WebCrypto so that
// a test can sign a nonce it has only just received.

const P = (1n << 255n) - 19n;

function modPow(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let b = base % P;
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % P;
    b = (b * b) % P;
    e >>= 1n;
  }
  return result;
}

/** The Curve25519 form (what libsignal calls the public key) of an Ed25519 public key. */
function edwardsToMontgomery(edwards: Uint8Array): Uint8Array {
  const yBytes = edwards.slice();
  yBytes[31] = yBytes[31]! & 0x7f;
  let y = 0n;
  for (let i = 31; i >= 0; i--) y = (y << 8n) | BigInt(yBytes[i]!);
  // u = (1 + y) / (1 - y)
  let u = (((1n + y) % P) * modPow((1n - y + P) % P, P - 2n)) % P;
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) {
    out[i] = Number(u & 0xffn);
    u >>= 8n;
  }
  return out;
}

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export type Identity = { mailboxId: string; publicKey: Uint8Array; sign(message: Uint8Array): Promise<Uint8Array> };

/** An identity key that signs the way libsignal does (XEdDSA: sign bit in the last byte). */
export async function makeIdentity(): Promise<Identity> {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const edwards = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  const signBit = edwards[31]! >> 7;
  const publicKey = edwardsToMontgomery(edwards);
  return {
    mailboxId: toHex(publicKey),
    publicKey,
    async sign(message) {
      const signature = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, message));
      signature[63] = signature[63]! | (signBit << 7);
      return signature;
    },
  };
}

function p1363ToDer(signature: Uint8Array): Uint8Array {
  const integer = (bytes: Uint8Array): number[] => {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start++;
    const digits = [...bytes.subarray(start)];
    if (digits[0]! & 0x80) digits.unshift(0);
    return [0x02, digits.length, ...digits];
  };
  const body = [...integer(signature.subarray(0, 32)), ...integer(signature.subarray(32))];
  return new Uint8Array([0x30, body.length, ...body]);
}

export type Device = { spki: Uint8Array; sign(message: Uint8Array): Promise<Uint8Array> };

/** A device key that signs the way Android Keystore does (P-256, ASN.1 DER output). */
export async function makeDevice(): Promise<Device> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ])) as CryptoKeyPair;
  const spki = new Uint8Array((await crypto.subtle.exportKey("spki", pair.publicKey)) as ArrayBuffer);
  return {
    spki,
    async sign(message) {
      const raw = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, message);
      return p1363ToDer(new Uint8Array(raw));
    },
  };
}

export async function registerFrame(identity: Identity, device: Device, nonce: Uint8Array) {
  const transcript = registerTranscript(fromHex(identity.mailboxId), nonce, device.spki);
  return {
    t: "register",
    deviceKey: toBase64(device.spki),
    identitySig: toBase64(await identity.sign(transcript)),
    deviceSig: toBase64(await device.sign(transcript)),
  };
}

export async function loginFrame(identity: Identity, device: Device, nonce: Uint8Array) {
  const transcript = loginTranscript(fromHex(identity.mailboxId), nonce);
  return { t: "login", sig: toBase64(await device.sign(transcript)) };
}

/** Connects and creates the account. Returns the logged-in socket. */
export async function register(identity: Identity, device: Device): Promise<Inbox> {
  const { inbox, nonce, registered } = await connect(identity.mailboxId);
  expect(registered).toBe(false);
  inbox.sendJson(await registerFrame(identity, device, nonce));
  expect(await inbox.nextJson()).toEqual({
    t: "ready",
    fresh: true,
    keys: false,
    otks: 0,
    push: false,
    chatPush: false,
    pushStatus: null,
    vapid: expect.any(String),
    queued: 0,
    email: null,
    name: null,
    verified: false,
    files: expect.any(String),
  });
  return inbox;
}

/** Connects and logs in to an existing account. Returns the logged-in socket. */
export async function login(identity: Identity, device: Device): Promise<Inbox> {
  const { inbox, nonce, registered } = await connect(identity.mailboxId);
  expect(registered).toBe(true);
  inbox.sendJson(await loginFrame(identity, device, nonce));
  expect(await inbox.nextJson()).toMatchObject({ t: "ready", fresh: false });
  return inbox;
}

/** Reads the error frame and the close that must follow a refused login or registration. */
export async function expectRefused(inbox: Inbox, code: string): Promise<void> {
  expect(await inbox.nextJson()).toEqual({ t: "error", code });
  expect((await inbox.closed).code).toBe(4003);
}

// ---- Messaging helpers -----------------------------------------------------

export type User = { identity: Identity; device: Device; id: string };

export async function makeUser(): Promise<User> {
  const identity = await makeIdentity();
  return { identity, device: await makeDevice(), id: identity.mailboxId };
}

/** Creates an account and leaves it offline. */
export async function registered(): Promise<User> {
  const user = await makeUser();
  const inbox = await register(user.identity, user.device);
  await hangUp(inbox);
  return user;
}

export async function hangUp(inbox: Inbox): Promise<void> {
  inbox.ws.close(1000, "done");
  await inbox.closed;
}

export function randomBytes(length: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(length));
}

let requestCounter = 0;

export function requestId(): string {
  requestCounter += 1;
  return `r${requestCounter}`;
}

/** Sends a message and returns the relay's answer to it. */
export async function sendMessage(
  from: Inbox,
  to: string,
  body: Uint8Array,
  kind = 2,
): Promise<Record<string, unknown>> {
  const id = requestId();
  from.sendJson({ t: "send", id, to, kind, body: toBase64(body) });
  const answer = await from.nextJson();
  expect(answer.id).toBe(id);
  return answer;
}

/** Key material of the right sizes. The relay stores it without looking inside. */
export function keysFrame(id: string = requestId()) {
  return {
    t: "put_keys",
    id,
    regId: 4242,
    spk: { id: 1, key: toBase64(randomBytes(33)), sig: toBase64(randomBytes(64)) },
    kpk: { id: 1, key: toBase64(randomBytes(1569)), sig: toBase64(randomBytes(64)) },
  };
}

export function oneTimeKeys(firstId: number, count: number) {
  return Array.from({ length: count }, (_, i) => ({ id: firstId + i, key: toBase64(randomBytes(33)) }));
}

// ---- Wake-up helpers -------------------------------------------------------

function fromBase64Url(text: string): Uint8Array {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(text.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

export { fromBase64Url };

async function hkdf(salt: Uint8Array, secret: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, length * 8));
}

/** The receiving side of Web Push: what a phone's push app holds. */
export type PushKeys = { publicKey: Uint8Array; auth: Uint8Array; decrypt(body: Uint8Array): Promise<Uint8Array> };

/**
 * Makes the keys a push app would hand out, with the matching decryption
 * (RFC 8291 read from the receiver's side, written separately from the
 * relay's encryption so that one checks the other).
 */
export async function makePushKeys(): Promise<PushKeys> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, [
    "deriveBits",
  ])) as CryptoKeyPair;
  const publicKey = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  const auth = randomBytes(16);
  return {
    publicKey,
    auth,
    async decrypt(body) {
      const salt = body.subarray(0, 16);
      const keyLength = body[20]!;
      const senderPublic = body.subarray(21, 21 + keyLength);
      const sealed = body.subarray(21 + keyLength);
      const sender = await crypto.subtle.importKey("raw", senderPublic, { name: "ECDH", namedCurve: "P-256" }, false, []);
      const ecdh = { name: "ECDH", public: sender } as unknown as SubtleCryptoDeriveKeyAlgorithm;
      const shared = new Uint8Array(await crypto.subtle.deriveBits(ecdh, pair.privateKey, 256));
      const text = new TextEncoder();
      const info = new Uint8Array([...text.encode("WebPush: info\0"), ...publicKey, ...senderPublic]);
      const material = await hkdf(auth, shared, info, 32);
      const contentKey = await hkdf(salt, material, text.encode("Content-Encoding: aes128gcm\0"), 16);
      const nonce = await hkdf(salt, material, text.encode("Content-Encoding: nonce\0"), 12);
      const aes = await crypto.subtle.importKey("raw", contentKey, "AES-GCM", false, ["decrypt"]);
      const record = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, aes, sealed));
      // Strip the padding: everything from the last 0x02 on.
      return record.subarray(0, record.lastIndexOf(2));
    },
  };
}
