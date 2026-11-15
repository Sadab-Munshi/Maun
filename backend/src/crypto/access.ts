// Checks a Cloudflare Access token: the proof that somebody went through the
// email login in front of this relay.
//
// The token is a JWT signed by the Access team's key (RS256). It is accepted
// only when the signature verifies against the team's published keys, it was
// issued by that team for this application, and it has not expired.

/** Where to check tokens against: the team's address and the application's tag. */
export type AccessConfig = { team: string; aud: string };

/** Longest token read at all. Access tokens are well under 2,000 characters. */
export const MAX_ACCESS_TOKEN_CHARS = 4096;
const TOKEN_SHAPE = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

/** The team's keys are fetched again after this long, or when a token names one not yet seen. */
const KEYS_FRESH_MS = 60 * 60 * 1000;
const KEYS_RETRY_MS = 60 * 1000;

type Keys = { byId: Map<string, CryptoKey>; at: number };
const keyCache = new Map<string, Keys>();

/** For tests: forget the keys fetched so far. */
export function forgetAccessKeys(): void {
  keyCache.clear();
}

/**
 * Reads the two settings that switch member-only mode on. `ACCESS_TEAM` may be
 * a team name, the team's host name, or its address.
 */
export function accessConfig(env: unknown): AccessConfig | null {
  const vars = env as { ACCESS_TEAM?: unknown; ACCESS_AUD?: unknown };
  if (typeof vars.ACCESS_TEAM !== "string" || typeof vars.ACCESS_AUD !== "string") return null;
  let team = vars.ACCESS_TEAM.trim().toLowerCase().replace(/^https:\/\//, "").replace(/\/+$/, "");
  const aud = vars.ACCESS_AUD.trim();
  if (team === "" || aud === "") return null;
  if (!team.includes(".")) team = `${team}.cloudflareaccess.com`;
  if (!/^[a-z0-9.-]+$/.test(team)) return null;
  return { team, aud };
}

export function looksLikeAccessToken(token: unknown): token is string {
  return typeof token === "string" && token.length <= MAX_ACCESS_TOKEN_CHARS && TOKEN_SHAPE.test(token);
}

/** The email address the token was issued to, in lower case, or null when the token is not acceptable. */
export async function verifyAccessToken(token: string, config: AccessConfig, now: number): Promise<string | null> {
  if (!looksLikeAccessToken(token)) return null;
  const [headerPart, payloadPart, signaturePart] = token.split(".") as [string, string, string];
  const header = parseJson(headerPart);
  const payload = parseJson(payloadPart);
  if (!header || !payload) return null;
  if (header.alg !== "RS256" || typeof header.kid !== "string") return null;

  if (payload.iss !== `https://${config.team}`) return null;
  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(config.aud)) return null;
  const seconds = Math.floor(now / 1000);
  if (typeof payload.exp !== "number" || payload.exp <= seconds) return null;
  // A minute of leeway for clocks that disagree.
  if (typeof payload.nbf === "number" && payload.nbf > seconds + 60) return null;
  const email = typeof payload.email === "string" ? payload.email.trim().toLowerCase() : "";
  // Service tokens carry no email; they are for machines, not members.
  if (email === "" || email.length > 254 || !email.includes("@")) return null;

  const key = await signingKey(config.team, header.kid, now);
  if (!key) return null;
  const signature = fromBase64Url(signaturePart);
  if (!signature) return null;
  const signed = new TextEncoder().encode(`${headerPart}.${payloadPart}`);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, signed);
  return ok ? email : null;
}

async function signingKey(team: string, keyId: string, now: number): Promise<CryptoKey | null> {
  let keys = keyCache.get(team);
  const stale = !keys || now - keys.at > KEYS_FRESH_MS;
  const unknown = keys && !keys.byId.has(keyId) && now - keys.at > KEYS_RETRY_MS;
  if (stale || unknown) {
    const fetched = await fetchKeys(team, now);
    // Keep the old keys when the team's address cannot be reached.
    if (fetched) {
      keys = fetched;
      keyCache.set(team, fetched);
    }
  }
  return keys?.byId.get(keyId) ?? null;
}

async function fetchKeys(team: string, now: number): Promise<Keys | null> {
  try {
    const response = await fetch(`https://${team}/cdn-cgi/access/certs`, { signal: AbortSignal.timeout(10_000) });
    if (!response.ok) return null;
    const body = (await response.json()) as { keys?: unknown };
    if (!Array.isArray(body.keys)) return null;
    const byId = new Map<string, CryptoKey>();
    for (const entry of body.keys.slice(0, 10)) {
      const jwk = entry as JsonWebKey & { kid?: unknown };
      if (typeof jwk.kid !== "string" || jwk.kty !== "RSA") continue;
      try {
        const key = await crypto.subtle.importKey(
          "jwk",
          { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
          { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
          false,
          ["verify"],
        );
        byId.set(jwk.kid, key);
      } catch {
        // A key this runtime cannot read is skipped.
      }
    }
    return { byId, at: now };
  } catch {
    return null;
  }
}

function fromBase64Url(text: string): Uint8Array | null {
  try {
    const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4);
    const binary = atob(padded);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function parseJson(part: string): Record<string, unknown> | null {
  const bytes = fromBase64Url(part);
  if (!bytes) return null;
  try {
    const value: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
