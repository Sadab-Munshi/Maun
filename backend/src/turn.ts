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

/**
 * Asks Cloudflare for credentials. Returns the servers, or the HTTP status
 * that came back instead (0 when the service could not be reached).
 */
export async function iceServers(config: TurnConfig): Promise<IceServer[] | number> {
  let response: Response;
  try {
    response = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(config.keyId)}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { Authorization: `Bearer ${config.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return 0;
  }
  if (!response.ok) {
    await response.body?.cancel();
    return response.status;
  }
  let body: { iceServers?: unknown };
  try {
    body = (await response.json()) as { iceServers?: unknown };
  } catch {
    return 502;
  }
  const listed = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
  const servers: IceServer[] = [];
  for (const entry of listed) {
    if (typeof entry !== "object" || entry === null) continue;
    const { urls, username, credential } = entry as Record<string, unknown>;
    const all = (Array.isArray(urls) ? urls : [urls]).filter((url): url is string => typeof url === "string");
    // Only relays are of use: the phones are told to connect through one and nothing else.
    const usable = all.filter((url) => /^turns?:/.test(url));
    if (usable.length === 0 || typeof username !== "string" || typeof credential !== "string") continue;
    servers.push({ urls: usable, username, credential });
  }
  return servers.length > 0 ? servers : 502;
}
