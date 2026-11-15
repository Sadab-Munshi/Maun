import { DurableObject } from "cloudflare:workers";
import { PROTOCOL_VERSION, parseClientFrame, type ServerFrame } from "./protocol";

/** Close code sent to an older socket when the same mailbox connects again. */
export const CLOSE_REPLACED = 4001;

/**
 * One Mailbox per user, addressed by the user's public key.
 *
 * Sockets are accepted with the WebSocket Hibernation API, so an idle
 * connection costs no Durable Object duration. Clients keep the connection
 * alive with WebSocket protocol pings, which the runtime answers without waking
 * this object. Nothing may be kept in instance
 * fields between events: the object can be evicted and rebuilt at any time
 * while its sockets stay open.
 */
export class Mailbox extends DurableObject<Env> {
  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    // One live connection per mailbox. A reconnect replaces the old socket.
    for (const old of this.ctx.getWebSockets()) {
      old.close(CLOSE_REPLACED, "replaced");
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    send(server, { t: "hello", proto: PROTOCOL_VERSION });
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const frame = parseClientFrame(raw);
    if (typeof frame === "string") {
      send(ws, { t: "error", code: frame });
      return;
    }
    // Milestone 0 only: echo proves the round trip. Removed when login lands.
    send(ws, { t: "echo", id: frame.id, body: frame.body, at: Date.now() });
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      // 1005 and 1006 are reserved and cannot be sent back.
      ws.close(code === 1005 || code === 1006 ? 1000 : code, reason);
    } catch {
      // Already closed by the runtime.
    }
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    try {
      ws.close(1011, "error");
    } catch {
      // Already closed.
    }
  }
}

function send(ws: WebSocket, frame: ServerFrame): void {
  ws.send(JSON.stringify(frame));
}
