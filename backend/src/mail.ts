// Sends the relay's few emails: login codes, and a note to the administrator
// when somebody asks to join. Two services are supported, chosen by which key
// is set: Resend (RESEND_API_KEY) or Brevo (BREVO_API_KEY).
//
// Resend delivers to other people only from a domain you have verified with
// it. Without one it sends from onboarding@resend.dev and only to the address
// of the Resend account itself, which is enough to try the administrator's
// own login and nothing more.

export type MailConfig = { service: "resend" | "brevo"; key: string; from: string };

const RESEND_TEST_SENDER = "Maun <onboarding@resend.dev>";

/** Reads the mail settings. Null when no service is configured. */
export function mailConfig(env: unknown): MailConfig | null {
  const vars = env as { RESEND_API_KEY?: unknown; BREVO_API_KEY?: unknown; MAIL_FROM?: unknown; ADMIN_EMAIL?: unknown };
  const text = (value: unknown) => (typeof value === "string" ? value.trim() : "");
  const from = text(vars.MAIL_FROM);
  if (text(vars.RESEND_API_KEY) !== "") {
    return { service: "resend", key: text(vars.RESEND_API_KEY), from: from || RESEND_TEST_SENDER };
  }
  if (text(vars.BREVO_API_KEY) !== "") {
    // Brevo sends from an address verified with it; the administrator's own is the natural one.
    const sender = from || text(vars.ADMIN_EMAIL);
    return sender === "" ? null : { service: "brevo", key: text(vars.BREVO_API_KEY), from: sender };
  }
  return null;
}

/** Splits "Name <address>" into its parts. A bare address has no name. */
export function parseSender(from: string): { name: string; email: string } {
  const match = /^\s*(.*?)\s*<([^<>]+)>\s*$/.exec(from);
  return match ? { name: match[1] || "Maun", email: match[2]!.trim() } : { name: "Maun", email: from.trim() };
}

/** Sends one plain-text email. Returns the service's HTTP status, or 0 when it could not be reached. */
export async function sendMail(config: MailConfig, to: string, subject: string, text: string): Promise<number> {
  try {
    const response =
      config.service === "resend"
        ? await fetch("https://api.resend.com/emails", {
            method: "POST",
            headers: { Authorization: `Bearer ${config.key}`, "Content-Type": "application/json" },
            body: JSON.stringify({ from: config.from, to: [to], subject, text }),
            signal: AbortSignal.timeout(10_000),
          })
        : await fetch("https://api.brevo.com/v3/smtp/email", {
            method: "POST",
            headers: { "api-key": config.key, "Content-Type": "application/json", Accept: "application/json" },
            body: JSON.stringify({ sender: parseSender(config.from), to: [{ email: to }], subject, textContent: text }),
            signal: AbortSignal.timeout(10_000),
          });
    // Read and drop the body so the connection can be reused.
    await response.arrayBuffer().catch(() => {});
    return response.status;
  } catch {
    return 0;
  }
}
