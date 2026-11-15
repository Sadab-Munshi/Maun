import { PROTOCOL_VERSION, isMailboxId } from "./protocol";

export { Mailbox } from "./mailbox";

/**
 * Stateless front door. It never logs, and it stores nothing: every request is
 * either answered here or handed to the Mailbox that owns the address.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({ ok: true, service: "maun-relay", proto: PROTOCOL_VERSION });
    }

    const ws = /^\/v1\/ws\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && ws) {
      const mailboxId = ws[1] ?? "";
      if (!isMailboxId(mailboxId)) return new Response("bad mailbox id", { status: 400 });
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }
      const stub = env.MAILBOX.get(env.MAILBOX.idFromName(mailboxId));
      return stub.fetch(request);
    }

    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
