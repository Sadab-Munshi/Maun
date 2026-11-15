// Calls go through Cloudflare's TURN service, so that the two phones never
// learn each other's address. The relay holds the long-term key
// (TURN_KEY_ID and TURN_API_TOKEN) and hands a phone short-lived credentials
// made from it. What is said in a call never passes through the relay, and
// the TURN service carries it encrypted between the phones.

export type TurnConfig = { keyId: string; token: string };

/** One server entry as WebRTC wants it. */
export type IceServer = { urls: string[]; username?: string; credential?: string };

/** How long the credentials of one call work, in seconds. A call cannot outlast them. */
export const TURN_TTL_SECONDS = 4 * 60 * 60;

/** Credentials one account may ask for in a day. A call takes one on each side. */
export const TURN_DAILY_CAP = 60;

/** Reads the TURN settings. Null when calls are not set up. */
export function turnConfig(env: unknown): TurnConfig | null {
  const vars = env as { TURN_KEY_ID?: unknown; TURN_API_TOKEN?: unknown };
  const text = (value: unknown) => (typeof value === "string" ? value.trim() : "");
  const keyId = text(vars.TURN_KEY_ID);
  const token = text(vars.TURN_API_TOKEN);
  return keyId !== "" && token !== "" ? { keyId, token } : null;
}

/** What one request to the call service came to. `shape` describes the answer without anything secret in it. */
export type TurnAnswer = { status: number; servers: IceServer[]; shape: string };

/** Asks Cloudflare for credentials with the key and token exactly as given. */
async function request(keyId: string, token: string): Promise<TurnAnswer> {
  let response: Response;
  try {
    response = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return { status: 0, servers: [], shape: "unreachable" };
  }
  if (!response.ok) {
    await response.body?.cancel();
    return { status: response.status, servers: [], shape: "refused" };
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { status: response.status, servers: [], shape: "not json" };
  }
  const field = typeof body === "object" && body !== null ? (body as { iceServers?: unknown }).iceServers : undefined;
  const listed = Array.isArray(field) ? field : field === undefined ? [] : [field];
  const servers: IceServer[] = [];
  const seen: string[] = [];
  for (const entry of listed) {
    if (typeof entry !== "object" || entry === null) continue;
    const { urls, username, credential } = entry as Record<string, unknown>;
    const all = (Array.isArray(urls) ? urls : [urls]).filter((url): url is string => typeof url === "string");
    seen.push(`${all.map((url) => url.split(":")[0]).join("+")}${typeof username === "string" ? " with user" : ""}`);
    // Only relays are of use: the phones are told to connect through one and nothing else.
    const usable = all.filter((url) => /^turns?:/.test(url));
    if (usable.length === 0 || typeof username !== "string" || typeof credential !== "string") continue;
    servers.push({ urls: usable, username, credential });
  }
  const keys = typeof body === "object" && body !== null ? Object.keys(body).join(",") : typeof body;
  return { status: response.status, servers, shape: `keys ${keys}; servers [${seen.join("; ")}]` };
}

// Whether the two settings turned out to be the other way round, once that is known.
let swapped: boolean | null = null;

/**
 * Asks Cloudflare for credentials. The dashboard shows two long strings side
 * by side, and they are easily put into each other's place: when the service
 * turns the pair away, it is tried the other way round, and what works is
 * remembered.
 */
export async function askTurn(config: TurnConfig): Promise<TurnAnswer & { swapped: boolean }> {
  const inOrder = (flip: boolean) => (flip ? request(config.token, config.keyId) : request(config.keyId, config.token));
  if (swapped !== null) return { ...(await inOrder(swapped)), swapped };
  const first = await inOrder(false);
  if (first.servers.length > 0) {
    swapped = false;
    return { ...first, swapped: false };
  }
  if (first.status >= 400 && first.status < 500) {
    const second = await inOrder(true);
    if (second.servers.length > 0) {
      swapped = true;
      return { ...second, swapped: true };
    }
  }
  return { ...first, swapped: false };
}

/** For tests: forget which way round the settings work. */
export function forgetTurnOrder(): void {
  swapped = null;
}

/**
 * The servers for one call, or the HTTP status that came back instead (0 when
 * the service could not be reached, 502 when its answer held no usable server).
 */
export async function iceServers(config: TurnConfig): Promise<IceServer[] | number> {
  const answer = await askTurn(config);
  if (answer.servers.length > 0) return answer.servers;
  return answer.status >= 200 && answer.status < 300 ? 502 : answer.status;
}
