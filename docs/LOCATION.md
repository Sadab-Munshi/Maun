# Live location

Saying where you are with one person, two ways: a place sent once, and a share
that follows you for as long as getting there takes. This is the v1: on purpose
the smallest thing that is honest about what it does, and no more.

## The two ways

The paperclip offers both, and they are not the same thing.

| | **Send my location now** | **Share live** |
|---|---|---|
| What travels | one ordinary encrypted message | a relay row, with sealed points on it |
| How long it lasts | forever, as a message | 15 minutes to a day, then nothing |
| What runs on the sending phone | nothing | a foreground service and a notification |
| What the other phone does | nothing: it already has it | asks the relay while its card is on screen |
| Anything to end | no | yes: Stop, the clock, or the relay |

A place sent once answers "where are you?" nine times out of ten, and costs
neither battery nor a thing kept on the relay. A share is for the other time:
the walk home, the night out, the long drive.

## What a share is

- The paperclip in a conversation, **Location**, then **15 minutes**, **1 hour**,
  **4 hours**, **8 hours** or **24 hours**. That is the whole of the offer. The
  list runs to a day because this is a safety feature before it is a
  convenience: somebody walking home at night should not be told to start the
  share again every quarter of an hour.
- The phone asks Android for its location at that moment, and at no other time.
  Saying no leaves nothing running and tells the other phone nothing.
- Permission is one switch and the phone's own location is another. If the app
  is allowed the location but the phone has it switched off, nothing is sent: a
  dialog says so, with **Location settings** beside **Not now**, because a share
  that runs with no fix behind it is a card that never fills in. Maun does
  **not** switch the phone's location on by itself — Android only lets a system
  app do that, and it is the phone owner's switch, not the app's.
- While a share runs, the phone keeps a fix every ten seconds and sends a point
  when fifteen seconds have passed, or sooner if it has moved ten metres.
  Standing still it still says where it is every four minutes, so a viewer can
  tell "still here" from "signal gone".
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

The map is **OpenFreeMap's**, drawn on the phone by **MapLibre** — free
software, no key, no account, no Google. The style is one URL
(`https://tiles.openfreemap.org/styles/bright`); the tiles are OpenStreetMap
data packaged as vector tiles; the dot is drawn from the point itself, so the
map holds no copy of where anybody was. Attribution is on the map, and the card
under it says what the map is.

Two things changed here. On a phone running 0.25.0 the map was an empty grey
grid: osmdroid, which drew it then, keeps its tiles in a folder of its own, and
the one it picked by default is on shared storage that Android 10 and later
will not let an app write to. That was fixed in 0.26.0, and then the whole
renderer was replaced, because osmdroid draws raster tiles from
`tile.openstreetmap.org` — and OpenStreetMap's tile policy **forbids
distributing an app that uses that server** without their prior permission. A
map that can be switched off for every user at once, by somebody else, is not a
map a safety feature can stand on, whatever it looks like.

The trade with OpenFreeMap is the usual one for a map: the tile server learns
which part of the world this phone is looking at, and roughly when. It is a
small donation-funded instance, so it is one URL away from being replaced:
serving the same style from tiles this project rendered itself is a change to
`MAP_STYLE` and nothing else, because everything else in the map is the
drawing. If the map cannot load at all, the card still shows the coordinates and the
**Maps** button on the map screen hands the point to whatever maps app the
phone already has, which works whether or not a single tile was drawn.

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
| A place sent once | `Place` in `LiveLocation.kt`; the switch helpers in `android/.../Places.kt` |
| The routes from the phone | `android/.../network/LocationApi.kt` |
| The service and its notification | `android/.../push/LocationService.kt`, `push/Notifications.kt` |
| The card, the sheet and the map | `android/.../ui/LiveLocation.kt` (style URL, dot layer) |
| The tests | `backend/test/location.test.ts`, `android/.../LiveLocationTest.kt`, `LiveCardTextTest.kt` |

A place sent once touches nothing in `backend/`: it is a message like any
other, sealed with the conversation's own keys, and the relay cannot tell it
from a sentence.

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
- that the MapLibre map draws at all, that street names come up in a city and
  not just a beige field, and that the dot is where the phone is;
- that a day-long share is still being sent after an hour with the screen off,
  and what it costs a battery to move a point every fifteen seconds;
- that Android brings the share back by itself after it has put the app away,
  which is what the sticky service is for;
- that the sheet's four choices stay on one line each, "Location" included.
