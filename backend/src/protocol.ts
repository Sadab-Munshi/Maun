// Wire protocol shared by the Worker and the Mailbox Durable Object.
//
// Frames are JSON text. Milestone 0 has only the echo frame; real message
// envelopes arrive in Milestone 2 and will replace it.

/** Bumped whenever a frame changes in a way old clients cannot read. */
export const PROTOCOL_VERSION = 0;

/** Largest frame the mailbox will read. Chat envelopes are small; files go to R2. */
export const MAX_FRAME_BYTES = 4096;

/**
 * A mailbox ID is the user's raw 32-byte Curve25519 identity public key in
 * lower-case hex. The ID is the address: there is no user table to look up.
 */
const MAILBOX_ID = /^[0-9a-f]{64}$/;

export function isMailboxId(value: string): boolean {
  return MAILBOX_ID.test(value);
}

export type ClientFrame = { t: "echo"; id: string; body: string };

export type ServerFrame =
  | { t: "hello"; proto: number }
  | { t: "echo"; id: string; body: string; at: number }
  | { t: "error"; code: ErrorCode };

export type ErrorCode = "bad_frame" | "too_large" | "unknown_type";

/** Parses a client frame. Returns an error code instead of throwing. */
export function parseClientFrame(raw: string | ArrayBuffer): ClientFrame | ErrorCode {
  if (typeof raw !== "string") return "bad_frame";
  // Length in UTF-16 units is a cheap lower bound; the encoder gives the real size.
  if (raw.length > MAX_FRAME_BYTES || new TextEncoder().encode(raw).length > MAX_FRAME_BYTES) {
    return "too_large";
  }
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return "bad_frame";
  }
  if (typeof value !== "object" || value === null) return "bad_frame";
  const frame = value as Record<string, unknown>;
  if (typeof frame.t !== "string") return "bad_frame";
  if (frame.t === "echo") {
    if (typeof frame.id !== "string" || typeof frame.body !== "string") return "bad_frame";
    return { t: "echo", id: frame.id, body: frame.body };
  }
  return "unknown_type";
}
