// The administrator's page. One file, no outside scripts, fonts or images.
// Everything it shows about members is put on the page as text, never as HTML.

const STYLE = `
:root{color-scheme:light dark;--bg:#f7f4ee;--card:#fff;--ink:#17211d;--quiet:#5d6b64;--line:#d9d5cb;--brand:#2f6b55;--bad:#b3261e;--warn:#8a5a00}
@media (prefers-color-scheme:dark){:root{--bg:#0e1513;--card:#17211d;--ink:#e4ebe7;--quiet:#9fb0a8;--line:#2c3a34;--brand:#8fd3b6;--bad:#ffb4ab;--warn:#f0c36a}}
*{box-sizing:border-box}body{margin:0;font:16px/1.45 system-ui,sans-serif;background:var(--bg);color:var(--ink)}
main{max-width:860px;margin:0 auto;padding:20px 16px 60px}h1{font-size:22px;margin:0}h2{font-size:15px;color:var(--brand);margin:28px 0 8px}
p{margin:8px 0}.quiet{color:var(--quiet);font-size:14px}.top{display:flex;align-items:center;gap:12px;justify-content:space-between}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;margin:10px 0}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.grow{flex:1 1 220px;min-width:0}
input{font:inherit;padding:10px 12px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--ink);width:100%}
button{font:inherit;padding:9px 14px;border-radius:999px;border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer}
button.main{background:var(--brand);border-color:var(--brand);color:var(--bg);font-weight:600}button.bad{color:var(--bad)}button:disabled{opacity:.5;cursor:default}
.name{font-weight:600;overflow-wrap:anywhere}.tag{font-size:12px;border-radius:999px;padding:2px 8px;border:1px solid var(--line);color:var(--quiet);margin-left:6px;white-space:nowrap}
.tag.bad{color:var(--bad);border-color:var(--bad)}.tag.warn{color:var(--warn);border-color:var(--warn)}.mono{font-family:ui-monospace,monospace;font-size:12px;overflow-wrap:anywhere}
.error{color:var(--bad)}.hidden{display:none}.tabs{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0 4px}.tabs button.on{background:var(--ink);color:var(--bg);border-color:var(--ink)}
`;

const SETUP = `
<h1>Maun admin</h1>
<div class="card"><p><b>This relay is open to everyone, so there is nobody to manage yet.</b></p>
<p>To make it member-only, add these in the Cloudflare dashboard under
<b>Workers &amp; Pages &gt; maun &gt; Settings &gt; Variables and Secrets</b>, then press Deploy:</p>
<p><span class="mono">ADMIN_EMAIL</span> &nbsp;your own email address<br>
<span class="mono">RESEND_API_KEY</span> &nbsp;the API key from resend.com (add it as a Secret)<br>
<span class="mono">MAIL_FROM</span> &nbsp;for example <span class="mono">Maun &lt;login@your-domain.com&gt;</span>, an address on a domain you verified in Resend</p>
<p class="quiet">Without MAIL_FROM, Resend only delivers to the address of the Resend account itself. That is enough to
log in here as the administrator, and not enough for anyone else to get a code.</p></div>`;

const APP = `
<div class="top"><h1>Maun admin</h1><button id="logout" class="hidden">Log out</button></div>

<section id="login" class="hidden">
  <div class="card">
    <p>Log in with the administrator's email address. A code is sent there.</p>
    <div class="row"><div class="grow"><input id="email" type="email" autocomplete="email" placeholder="Email address"></div>
      <button id="send" class="main">Send code</button></div>
    <div id="codeRow" class="row hidden" style="margin-top:10px"><div class="grow"><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6-digit code"></div>
      <button id="enter" class="main">Log in</button></div>
    <p id="loginNote" class="quiet"></p>
  </div>
</section>

<section id="panel" class="hidden">
  <p id="summary" class="quiet"></p>
  <div class="card">
    <p><b>Approve someone in advance</b> <span class="quiet">They are let in as soon as they log in with this address.</span></p>
    <div class="row"><div class="grow"><input id="addEmail" type="email" placeholder="Email address"></div><button id="add" class="main">Add</button></div>
    <p id="addNote" class="quiet"></p>
  </div>
  <div class="tabs" id="tabs"></div>
  <div id="list"></div>
</section>`;

const SCRIPT = `
const $ = (id) => document.getElementById(id);
const show = (el, on) => el.classList.toggle("hidden", !on);
let members = [], tab = "waiting", busy = false;

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
  bad_email: "That does not look like an email address.",
  wrong_code: "That code is not right.",
  expired: "That code has run out. Ask for a new one.",
};
const explain = (r) => problems[r.data.error] || (r.status === 429 ? "Too many tries. Wait a minute." : "Something went wrong (" + r.status + ").");

function when(ms) {
  if (!ms) return "never";
  const minutes = Math.round((Date.now() - ms) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return minutes + " min ago";
  if (minutes < 1440) return Math.round(minutes / 60) + " h ago";
  return new Date(ms).toLocaleDateString();
}

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

const groups = {
  waiting: (m) => !m.banned && m.status === "pending",
  members: (m) => !m.banned && m.status === "approved",
  banned: (m) => m.banned,
};
const titles = { waiting: "Waiting", members: "Members", banned: "Banned" };

function draw() {
  const tabs = $("tabs");
  tabs.replaceChildren();
  for (const key of Object.keys(groups)) {
    const button = el("button", key === tab ? "on" : "", titles[key] + " (" + members.filter(groups[key]).length + ")");
    button.onclick = () => { tab = key; draw(); };
    tabs.append(button);
  }
  const list = $("list");
  list.replaceChildren();
  const shown = members.filter(groups[tab]);
  if (shown.length === 0) list.append(el("p", "quiet", tab === "waiting" ? "Nobody is waiting." : "Nobody here."));
  for (const m of shown) {
    const card = el("div", "card");
    const head = el("div");
    head.append(el("span", "name", m.username ? "@" + m.username : "(has not joined yet)"));
    if (m.admin) head.append(el("span", "tag", "admin"));
    if (m.banned) head.append(el("span", "tag bad", "banned"));
    if (m.deviceChangedAt) head.append(el("span", "tag warn", "phone changed " + when(m.deviceChangedAt)));
    card.append(head, el("div", "", m.email));
    const phone = m.device ? m.device + (m.devices > 1 ? " · " + m.devices + " phones seen" : "") : "no phone seen yet";
    card.append(el("div", "quiet", phone + " · last login " + when(m.lastLoginAt) + " · asked " + when(m.createdAt)));
    if (m.id) card.append(el("div", "mono quiet", "ID " + m.id));
    const actions = el("div", "row");
    actions.style.marginTop = "10px";
    const action = (label, name, cls, confirmText) => {
      const button = el("button", cls, label);
      button.onclick = async () => {
        if (busy || (confirmText && !confirm(confirmText))) return;
        busy = true;
        const r = await call("/v1/admin/members", { action: name, email: m.email });
        busy = false;
        if (r.status === 401) return start();
        await load();
      };
      actions.append(button);
    };
    if (!m.banned && m.status === "pending") action("Approve", "approve", "main");
    if (m.banned) action("Lift ban", "unban", "main");
    if (!m.admin && !m.banned) action("Ban", "ban", "bad", "Ban " + m.email + "? Their phone deletes its messages and files.");
    if (!m.admin) action(m.status === "pending" && !m.banned ? "Refuse" : "Remove", "remove", "", "Remove " + m.email + "? They would have to log in and be approved again.");
    card.append(actions);
    list.append(card);
  }
}

async function load() {
  const r = await call("/v1/admin/members");
  if (r.status === 401) { show($("panel"), false); show($("logout"), false); show($("login"), true); return false; }
  members = r.data.members || [];
  $("summary").textContent = "Logged in as " + r.data.admin + " · " + r.data.mailsToday + " of " + r.data.mailCap + " emails sent today";
  if (members.some(groups.waiting) === false && tab === "waiting") tab = "members";
  show($("login"), false); show($("panel"), true); show($("logout"), true);
  draw();
  return true;
}

async function start() { await load(); }

$("send").onclick = async () => {
  $("loginNote").className = "quiet";
  $("loginNote").textContent = "Sending…";
  const r = await call("/v1/auth/start", { email: $("email").value.trim() });
  if (r.status === 200) { show($("codeRow"), true); $("code").focus(); $("loginNote").textContent = "If that is the administrator's address, a code is on its way."; }
  else { $("loginNote").className = "error"; $("loginNote").textContent = explain(r); }
};
$("enter").onclick = async () => {
  const r = await call("/v1/admin/login", { email: $("email").value.trim(), code: $("code").value.trim() });
  if (r.status === 200) { $("code").value = ""; $("loginNote").textContent = ""; show($("codeRow"), false); tab = "waiting"; await load(); }
  else { $("loginNote").className = "error"; $("loginNote").textContent = explain(r); }
};
$("code").onkeydown = (e) => { if (e.key === "Enter") $("enter").click(); };
$("email").onkeydown = (e) => { if (e.key === "Enter") $("send").click(); };
$("add").onclick = async () => {
  const r = await call("/v1/admin/members", { action: "add", email: $("addEmail").value.trim() });
  if (r.status === 200) { $("addEmail").value = ""; $("addNote").textContent = "Added."; tab = "members"; await load(); }
  else if (r.status === 401) start();
  else { $("addNote").textContent = "That does not look like an email address."; }
};
$("logout").onclick = async () => { await call("/v1/admin/logout", {}); start(); };
start();
`;

export function adminPage(memberOnly: boolean): Response {
  const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
  const body = memberOnly ? `${APP}<script nonce="${nonce}">${SCRIPT}</script>` : SETUP;
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
