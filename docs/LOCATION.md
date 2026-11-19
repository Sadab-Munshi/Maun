# Live location

Sharing where you are, for a quarter of an hour or an hour, with one person.
This is the v1: on purpose the smallest thing that is honest about what it
does, and no more.

## What it is

- The paperclip in a conversation, **Location**, then **15 minutes** or
  **1 hour**. That is the whole of the offer.
- The phone asks Android for its location at that moment, and at no other time.
  Saying no leaves nothing running and tells the other phone nothing.
- A notification for the whole time it lasts: who can see it, how long is left,
  and **Stop**. It cannot be swiped away while the share runs.
- The other phone sees a card with **View**. Nothing is fetched until that is
  pressed — the accept is a real act, and the phone that shares is told when it
  happens.
- While the card is on screen it asks the relay for the newest point every ten
  seconds, which is as often as a point arrives, so a viewer watching a walk
  home sees the phone move. Nothing polls when the card is not on screen, and
  nothing polls once the share has ended.
- The share ends when somebody stops it, when the minute it was promised for
  passes, or when the relay says it has gone. Then the card keeps the fact that
  the share happened and none of the places.

## What the relay knows

One row per share, in the mailbox of the phone that **shares** it:

| Column | What it is |
|---|---|
| `id` | 16 random bytes, made on the phone |
| `viewer` | the one account allowed to read it |
| `expires` | the minute it ends, counted by the relay |
| `point` | the newest sealed point, or nothing |
| `point_at` | when that point was left |
| `stopped` | whether somebody ended it early |

The point is sealed on the phone with AES-GCM and a key of its own. That key
travels only inside an end-to-end encrypted message, so the relay stores a
latitude and a longitude it cannot open. It keeps **one**, not a trail: each
point replaces the last, and a point that arrives less than five seconds after
the previous one is refused without complaint.

What it can still see: that one account shares with another, when the share
started, how long it was for, and that points kept coming — a shape of activity,
not a place.

What it cannot do:

- read a point, or tell a share of a busy street from one of a field;
- answer anybody but the named viewer — `GET /v1/location/<sharer>/<share>`
  says `gone` to a stranger exactly as it would for a share that never existed;
- keep a point past its minute. The alarm clears the point itself, and the row
  is forgotten a day later, so "it ended, at 14:32" outlives the location but
  the location never does;
- carry a share across a phone that set Maun up again: `retire()` deletes them
  all, and a member the administrator has removed or banned has no shares the
  next time anybody asks.

## The routes

| Route | Who may call it | What it does |
|---|---|---|
| `POST /v1/location` | the phone that shares | `{to, minutes}` → `{share, until}` |
| `POST /v1/location/<share>` | the phone that shares | the sealed point, as the body; `410` when the share is over |
| `DELETE /v1/location/<share>` | the phone that shares | ends it now |
| `GET /v1/location/<sharer>/<share>` | the named viewer | `{state, until, point, at}`, or `{state:"ended"}` |

All four take the same credential as the file routes: `Authorization: Bearer
<mailbox id>.<token>`, where the token is handed out at each login. A banned
account is refused everywhere, at once.

Limits: a share is 15 minutes to a day, 1440 minutes (clamped in the mailbox,
not trusted from the request); a mailbox holds at most 8 shares; a point may be at most 512
bytes; one share per viewer, so a second offer replaces the first rather than
stacking two cards in one conversation.

## The card, in four states

| State | Card says | Can do |
|---|---|---|
| `SHARING` | "Sharing your location · until 14:32 · 12 min left" | Stop |
| `SHARING`, opened | "Ada is watching · until 14:32 · 12 min left" | Stop |
| `OFFER` | "Ada is sharing their location · until 14:32" | View |
| `WATCHING` | "Updated 20s ago · 51.50731, -0.12772 (±12 m)" | Open the map, Hide |
| `WATCHING`, quiet | "Updates paused — last update 3 min ago" | Hide |
| `ENDED` | "Ada shared their location · ended. Nothing of where anybody was is kept." | — |

Nothing of a place survives into `ENDED`, and a card that has gone quiet hides
the last point it had: a phone that lost its signal is not a phone that is
still standing there.

The phone that shares is told the moment the card is opened, and its own card
says **"Ada is watching"** from then on. Being told that somebody is looking is
half of what a share is for.

## The map

The map is OpenStreetMap's, drawn by osmdroid on the phone. It needs no key and
no Google, and nothing of it is fetched unless the map is open on screen.

osmdroid keeps its tiles in a folder of its own, and left alone it picks one on
shared storage that Android 10 and later will not let an app write to. Every
tile then fails and the map comes up as an empty grey grid with nothing on it —
which is exactly what it did on a phone running 0.25.0. Maun now tells it, once
before the first map is made, to keep its tiles under the app's own files, and
gives it a Maun user agent, which is what OSM's tile policy asks of an app.

The trade is the usual one for a map: the tile server learns which part of the
world this phone is looking at, and roughly when. The card and the map both say
so. Nothing else is sent with a request, and the tiles are the same ones any
other app asking for that area would get.

## What is not in v1

- No history: the point moves as the phone moves, and each new point replaces
  the last, but no line is drawn and no trail is kept.
- No "ask again", and no way for the viewer to start a share.
- No distance, direction or travel time, because the viewer's own location is
  never asked for. A viewer needs no permission at all.
- No sharing in a group: Maun has no groups.
- No wake-up for a new point: the viewer's card asks while it is on screen,
  which is the only time the answer is any use.

## Where it lives

| Part | Where |
|---|---|
| The relay's side, in the mailbox | `backend/src/mailbox.ts` — `shareStart`, `sharePoint`, `shareStop`, `sharePeek`, `forgetShares` |
| The routes | `backend/src/index.ts` — `startShare`, `putSharePoint`, `stopShare`, `peekShare` |
| The numbers | `backend/src/protocol.ts` — the live-location section |
| The phone's sealing, records and states | `android/.../LiveLocation.kt` |
| The routes from the phone | `android/.../network/LocationApi.kt` |
| The service and its notification | `android/.../push/LocationService.kt`, `push/Notifications.kt` |
| The card, the sheet and the map | `android/.../ui/LiveLocation.kt` |
| The tests | `backend/test/location.test.ts`, `android/.../LiveLocationTest.kt`, `LiveCardTextTest.kt` |

## Still to be seen on a phone

Everything here has been proved against the relay in Cloudflare's runtime and
in unit tests on the JVM. These have not been seen on a handset, and are the
first things to look at:

- that the notification appears at once, and that **Stop** in it ends the share
  on both phones;
- that a share survives the screen going off for a quarter of an hour — and
  what it costs in battery, on a phone that allows it and on one that fights
  background work;
- that the card recovers when the signal comes back, and says "updates paused"
  rather than a place that has gone stale;
- that the map draws from OSM tiles on a phone with no Google Play services —
  the blank grey grid of 0.25.0 should be gone, and this is the first thing to
  look at;
- that a day-long share is still being sent after an hour with the screen off,
  and what it costs a battery to move a point every fifteen seconds;
- that Android brings the share back by itself after it has put the app away,
  which is what the sticky service is for;
- that the sheet's four choices stay on one line each, "Location" included.
