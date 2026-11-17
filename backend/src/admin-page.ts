// The administrator's page. One file, no outside scripts, fonts or images.
// Everything it shows about members is put on the page as text, never as HTML.

const STYLE = `
:root{color-scheme:light dark;--bg:#f7f4ee;--card:#fff;--ink:#17211d;--quiet:#5d6b64;--line:#d9d5cb;--brand:#2f6b55;--bad:#b3261e;--warn:#8a5a00;--row:#f1eee7}
@media (prefers-color-scheme:dark){:root{--bg:#0e1513;--card:#17211d;--ink:#e4ebe7;--quiet:#9fb0a8;--line:#2c3a34;--brand:#8fd3b6;--bad:#ffb4ab;--warn:#f0c36a;--row:#1c2823}}
*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,sans-serif;background:var(--bg);color:var(--ink)}
main{max-width:1400px;margin:0 auto;padding:20px 16px 60px}h1{font-size:22px;margin:0}
p{margin:8px 0}.quiet{color:var(--quiet);font-size:13px}.top{display:flex;align-items:center;gap:12px;justify-content:space-between}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;margin:10px 0}.narrow{max-width:460px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.grow{flex:1 1 220px;min-width:0}
input{font:inherit;padding:9px 12px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--ink);width:100%}
button{font:inherit;padding:7px 12px;border-radius:999px;border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer;white-space:nowrap}
button.main{background:var(--brand);border-color:var(--brand);color:var(--bg);font-weight:600}button.bad{color:var(--bad)}button:disabled{opacity:.5;cursor:default}
.tag{display:inline-block;font-size:12px;border-radius:999px;padding:1px 8px;border:1px solid var(--line);color:var(--quiet);white-space:nowrap}
.tag.bad{color:var(--bad);border-color:var(--bad)}.tag.warn{color:var(--warn);border-color:var(--warn)}.tag.ok{color:var(--brand);border-color:var(--brand)}
.mono{font-family:ui-monospace,monospace;font-size:12px}.error{color:var(--bad)}.hidden{display:none}
.tabs{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0 8px}.tabs button.on{background:var(--ink);color:var(--bg);border-color:var(--ink)}
.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:14px;background:var(--card)}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top;white-space:nowrap}
th{font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--quiet);background:var(--row);position:sticky;top:0;white-space:nowrap}
tr:last-child td{border-bottom:0}td.acts{white-space:nowrap}td.acts button{margin:0 4px 4px 0;padding:5px 10px;font-size:13px}
.name{font-weight:600}.sub{color:var(--quiet);font-size:13px}td.num{text-align:right;font-variant-numeric:tabular-nums}
`;

const page = (inner: string) => `<h1>Maun admin</h1><div class="card narrow">${inner}</div>`;

const OPEN = page(`<p><b>This relay is open to everyone, so there is nobody to manage yet.</b></p>
<p>To make it member-only, add these in the Cloudflare dashboard under
<b>Workers &amp; Pages &gt; maun &gt; Settings &gt; Variables and Secrets</b>, then press Deploy:</p>
<p><span class="mono">ADMIN_EMAIL</span> your own email address<br>
<span class="mono">ADMIN_PASSWORD</span> (Secret) a password of 10 characters or more, for this page<br>
<span class="mono">RESEND_API_KEY</span> (Secret) the API key from resend.com<br>
<span class="mono">MAIL_FROM</span> for example <span class="mono">Maun &lt;login@your-domain.com&gt;</span></p>`);

const NO_PASSWORD = page(`<p><b>This page is closed until it has a password.</b></p>
<p>In the Cloudflare dashboard under <b>Workers &amp; Pages &gt; maun &gt; Settings &gt; Variables and Secrets</b>,
add a <b>Secret</b> named <span class="mono">ADMIN_PASSWORD</span> with a password of 10 characters or more, then press Deploy.</p>
<p class="quiet">Logging in then takes the administrator's email address, this password, and a code sent to that address.</p>`);

const APP = `
<div class="top"><h1>Maun admin</h1><button id="logout" class="hidden">Log out</button></div>

<section id="login" class="hidden">
  <div class="card narrow">
    <p>Log in with the administrator's email address and password. A code is then sent to that address.</p>
    <p><input id="email" type="email" autocomplete="username" placeholder="Email address"></p>
    <p><input id="password" type="password" autocomplete="current-password" placeholder="Password"></p>
    <button id="send" class="main">Continue</button>
    <div id="codeRow" class="hidden" style="margin-top:14px">
      <p><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6-digit code from the email"></p>
      <button id="enter" class="main">Log in</button>
    </div>
    <p id="loginNote" class="quiet"></p>
  </div>
</section>

<section id="panel" class="hidden">
  <p id="summary" class="quiet"></p>
  <p id="callsNote" class="quiet"></p>
  <p class="quiet">The verified tick is yours to give: it says the relay's owner has looked at an account.
  It is not a check of who somebody is, and it has nothing to do with the safety number a phone checks itself.</p>
  <div class="row">
    <div class="grow"><input id="search" type="search" placeholder="Search username, email, phone or ID"></div>
    <div class="grow"><input id="addEmail" type="email" placeholder="Approve an email address in advance"></div>
    <button id="add" class="main">Add</button>
  </div>
  <p id="addNote" class="quiet"></p>
  <div class="tabs" id="tabs"></div>
  <div class="scroll"><table><thead id="head"></thead><tbody id="list"></tbody></table></div>
  <p id="empty" class="quiet"></p>
</section>`;

const SCRIPT = `
const $ = (id) => document.getElementById(id);
const show = (el, on) => el.classList.toggle("hidden", !on);
let members = [], devices = [], tab = "waiting", busy = false;

async function call(path, body) {
  const options = body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
  const response = await fetch(path, { credentials: "same-origin", ...options });
  let data = {};
  try { data = await response.json(); } catch (e) {}
  return { status: response.status, data };
}

const problems = {
  wait: "A code was sent a moment ago. Wait a minute before asking for another.",
  limit: "Too many codes today. Try again tomorrow.",
  mail_failed: "The email could not be sent. Check the mail settings of the relay.",
  wrong_password: "That email address or password is not right.",
  locked: "Too many wrong passwords. The page is closed for 15 minutes.",
  wrong_code: "That code is not right.",
  expired: "That code has run out. Start again.",
};
const explain = (r) => problems[r.data.error] || (r.status === 429 ? "Too many tries. Wait a minute." : "Something went wrong (" + r.status + ").");

function ago(ms) {
  if (!ms) return "never";
  const minutes = Math.round((Date.now() - ms) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return minutes + " min ago";
  if (minutes < 1440) return Math.round(minutes / 60) + " h ago";
  if (minutes < 43200) return Math.round(minutes / 1440) + " d ago";
  return new Date(ms).toLocaleDateString();
}
const exact = (ms) => (ms ? new Date(ms).toLocaleString() : "");
const short = (id) => (id ? id.slice(0, 8) + "…" + id.slice(-6) : "");

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
function cell(...parts) {
  const td = el("td");
  for (const part of parts) if (part) td.append(typeof part === "string" ? el("div", "", part) : part);
  return td;
}
function time(ms) {
  const td = el("td", "", ago(ms));
  td.title = exact(ms);
  return td;
}

const groups = {
  waiting: (m) => !m.banned && !m.deviceBanned && m.status === "pending",
  members: (m) => !m.banned && !m.deviceBanned && m.status === "approved",
  banned: (m) => m.banned || m.deviceBanned,
};
const titles = { waiting: "Waiting", members: "Members", banned: "Banned people", devices: "Banned phones" };

async function act(body, confirmText) {
  if (busy || (confirmText && !confirm(confirmText))) return;
  busy = true;
  const r = await call("/v1/admin/members", body);
  busy = false;
  if (r.status === 401) return load();
  if (r.status === 409) alert("That would lock the administrator out, so it was not done.");
  await load();
}
function button(label, cls, onClick) {
  const b = el("button", cls, label);
  b.onclick = onClick;
  return b;
}

function memberRow(m) {
  const tr = el("tr");
  const who = cell(el("div", "name", m.username ? "@" + m.username : "(not joined yet)"), el("div", "sub", m.email));
  tr.append(who);

  const state = el("td");
  const tag = (text, cls) => { state.append(el("span", "tag " + (cls || ""), text), " "); };
  if (m.admin) tag("admin", "ok");
  if (m.banned) tag("banned", "bad");
  if (m.deviceBanned) tag("phone banned", "bad");
  if (!m.banned && m.status === "pending") tag(m.id ? "waiting" : "not joined", "warn");
  if (!m.banned && !m.deviceBanned && m.status === "approved") tag(m.id ? "member" : "invited", "ok");
  if (m.verified && !m.banned && !m.deviceBanned) tag("verified", "ok");
  tr.append(state);

  const phone = cell(m.device || "not seen yet", m.appVersion ? el("div", "sub", "Maun " + m.appVersion) : null);
  if (m.deviceChangedAt) phone.append(el("span", "tag warn", "changed " + ago(m.deviceChangedAt)));
  tr.append(phone);

  const did = el("td", "mono", short(m.deviceId));
  did.title = m.deviceId || "";
  tr.append(did);

  tr.append(time(m.lastLoginAt), el("td", "num", String(m.logins)), el("td", "num", String(m.reinstalls)), el("td", "num", String(m.devices)));
  tr.append(time(m.createdAt), time(m.approvedAt));
  const id = el("td", "mono", short(m.id));
  id.title = m.id || "";
  tr.append(id);

  const acts = el("td", "acts");
  if (!m.banned && m.status === "pending") acts.append(button("Approve", "main", () => act({ action: "approve", email: m.email })));
  if (m.banned) acts.append(button("Lift ban", "main", () => act({ action: "unban", email: m.email })));
  if (m.status === "approved" && !m.banned && !m.deviceBanned) {
    acts.append(
      m.verified
        ? button("Take the tick away", "", () => act({ action: "unverify", email: m.email }, "Take the verified tick away from " + m.email + "? Their contacts stop showing it."))
        : button("Verify", "main", () => act({ action: "verify", email: m.email }, "Give " + m.email + " the verified tick? Every phone that has them as a contact shows a green tick beside their name.")),
    );
  }
  if (!m.admin && !m.banned) acts.append(button("Ban", "bad", () => act({ action: "ban", email: m.email }, "Ban " + m.email + "? Their phone deletes its messages and files.")));
  if (m.deviceId && m.deviceBanned) acts.append(button("Lift phone ban", "", () => act({ action: "unban_device", device: m.deviceId })));
  if (m.deviceId && !m.deviceBanned && !m.admin) acts.append(button("Ban phone", "bad", () => act({ action: "ban_device", device: m.deviceId }, "Ban this phone (" + (m.device || "unknown") + ")? Nobody can use Maun on it, whichever email address they log in with.")));
  if (!m.admin) acts.append(button(m.status === "pending" && !m.banned ? "Refuse" : "Remove", "", () => act({ action: "remove", email: m.email }, "Remove " + m.email + "? They would have to log in and be approved again.")));
  tr.append(acts);
  return tr;
}

function deviceRow(d) {
  const tr = el("tr");
  const id = el("td", "mono", short(d.id));
  id.title = d.id;
  const acts = el("td", "acts");
  acts.append(button("Lift phone ban", "main", () => act({ action: "unban_device", device: d.id })));
  tr.append(el("td", "", d.model || "unknown"), id, el("td", "", d.email || ""), time(d.bannedAt), acts);
  return tr;
}

function draw() {
  const tabs = $("tabs");
  tabs.replaceChildren();
  const counts = { waiting: members.filter(groups.waiting).length, members: members.filter(groups.members).length, banned: members.filter(groups.banned).length, devices: devices.length };
  for (const key of Object.keys(titles)) {
    const b = el("button", key === tab ? "on" : "", titles[key] + " (" + counts[key] + ")");
    b.onclick = () => { tab = key; draw(); };
    tabs.append(b);
  }
  const head = $("head"), list = $("list");
  head.replaceChildren();
  list.replaceChildren();
  const headings = tab === "devices"
    ? ["Phone", "Phone ID", "Last used by", "Banned", ""]
    : ["Member", "State", "Phone", "Phone ID", "Last login", "Logins", "Re-installs", "Phones", "Asked", "Approved", "Maun ID", ""];
  const row = el("tr");
  for (const h of headings) row.append(el("th", "", h));
  head.append(row);

  const wanted = $("search").value.trim().toLowerCase();
  const hit = (...values) => wanted === "" || values.some((v) => v && String(v).toLowerCase().includes(wanted));
  let shown = 0;
  if (tab === "devices") {
    for (const d of devices) if (hit(d.id, d.model, d.email)) { list.append(deviceRow(d)); shown++; }
  } else {
    for (const m of members.filter(groups[tab])) {
      if (hit(m.username, m.email, m.device, m.id, m.deviceId, m.appVersion)) { list.append(memberRow(m)); shown++; }
    }
  }
  $("empty").textContent = shown ? "" : wanted ? "Nothing matches." : tab === "waiting" ? "Nobody is waiting." : "Nobody here.";
}

async function load() {
  const r = await call("/v1/admin/members");
  if (r.status !== 200) { show($("panel"), false); show($("logout"), false); show($("login"), true); return false; }
  members = r.data.members || [];
  devices = r.data.bannedDevices || [];
  $("summary").textContent = "Logged in as " + r.data.admin + " · " + members.length + " people · " + r.data.mailsToday + " of " + r.data.mailCap + " emails sent today";
  if (!members.some(groups.waiting) && tab === "waiting") tab = "members";
  callsLine();
  show($("login"), false); show($("panel"), true); show($("logout"), true);
  draw();
  return true;
}

// Whether calls work, asked once: the answer does not change by itself.
let callsAsked = false;
async function callsLine() {
  if (callsAsked) return;
  callsAsked = true;
  const r = await call("/v1/calls/check");
  if (r.status !== 200) return;
  const text = !r.data.configured
    ? "Calls: not set up (no TURN key)."
    : r.data.working
      ? "Calls: working."
      : r.data.status === 404
        ? "Calls: not working. Cloudflare does not know the TURN key (HTTP 404). TURN_KEY_ID has to be the Turn Token ID of a key made under Realtime > TURN Server, not the ID of a Realtime app."
        : r.data.status === 401 || r.data.status === 403
          ? "Calls: not working. Cloudflare refused the TURN_API_TOKEN (HTTP " + r.data.status + ")."
          : "Calls: not working (the call service answered " + r.data.status + ").";
  $("callsNote").textContent = text;
  $("callsNote").className = r.data.configured && !r.data.working ? "error" : "quiet";
}

const note = (text, bad) => { $("loginNote").className = bad ? "error" : "quiet"; $("loginNote").textContent = text; };
$("send").onclick = async () => {
  note("One moment…");
  const r = await call("/v1/admin/start", { email: $("email").value.trim(), password: $("password").value });
  if (r.status === 200) { show($("codeRow"), true); $("code").focus(); note("A code is on its way to that address."); }
  else note(explain(r), true);
};
$("enter").onclick = async () => {
  const r = await call("/v1/admin/login", { email: $("email").value.trim(), code: $("code").value.trim() });
  if (r.status === 200) { $("code").value = ""; $("password").value = ""; note(""); show($("codeRow"), false); tab = "waiting"; await load(); }
  else note(explain(r), true);
};
$("code").onkeydown = (e) => { if (e.key === "Enter") $("enter").click(); };
$("password").onkeydown = (e) => { if (e.key === "Enter") $("send").click(); };
$("search").oninput = draw;
$("add").onclick = async () => {
  const r = await call("/v1/admin/members", { action: "add", email: $("addEmail").value.trim() });
  if (r.status === 200) { $("addEmail").value = ""; $("addNote").textContent = "Added. They are let in as soon as they log in with that address."; tab = "members"; await load(); }
  else if (r.status === 401) load();
  else $("addNote").textContent = "That does not look like an email address.";
};
$("logout").onclick = async () => { await call("/v1/admin/logout", {}); load(); };
load();
`;

/** `state`: "open" for a relay with no members, "no_password" when the page has no password yet. */
export function adminPage(state: "open" | "no_password" | "ready"): Response {
  const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
  const body = state === "ready" ? `${APP}<script nonce="${nonce}">${SCRIPT}</script>` : state === "open" ? OPEN : NO_PASSWORD;
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Maun admin</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`;
  return new Response(html, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Content-Security-Policy":
        `default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'; connect-src 'self'; ` +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}
