# The conversation screen: selecting, and reactions

## What a long press does now (Round 10)

Before, a long press on a bubble opened a floating popup with Copy, Save and
Delete, one message at a time. That popup is gone. A long press now starts a
selection, and the composer's place at the bottom of the screen becomes the
bar that does something with it:

* **The emoji bar**, when exactly one message is selected: 👍 ❤️ 😂 😢 🙏 — five,
  no more, so the row fits any phone without a second page of emoji to open.
  The emoji this phone already put on the message is marked, and tapping it
  again takes it off. (The wire still accepts the wider set an earlier build
  could send, and shows it; nothing here offers it.)
* **Reply** to the one selected message: a strip above the field says what is
  being answered, and the answer stands under a quote of it. Only the answered
  message's ID travels; the quoted words are drawn from each phone's own copy of
  the conversation, so a reply says no more than a message does.
* **Copy**, in time order, oldest first, for every text message selected — one
  message or ten, in the order they were written rather than the order the list
  shows them.
* **Forward** the selected words to somebody else: a list of the people one
  talks to, with the conversation they are already in left out, and nothing is
  sent until a name is tapped. Only words travel — a file is handed over with
  Share instead, which needs no copy of it and no second upload.
* **Share** for a selected file: the same read-only, short-lived content URI the
  file bubble already hands to another app, so nothing is copied or re-saved.
* **Save**, when the single selected message is a file: the same one-tap save to
  Gallery or Downloads/Maun as before.
* **Delete**, once for the whole selection, with one question that says how many
  messages it is and warns about any that have not been sent yet. Nothing is
  deleted until that one answer.

A tap while something is selected adds or removes that message instead of
opening a file, which is what makes a run of messages selectable without a long
press for each. `×` puts the composer back. Notices ("messages are encrypted on
this phone…") are not messages and cannot be selected.

## Reactions

A reaction is a message of its own, sealed with the same Double Ratchet session
as everything else, so the relay carries it without learning who felt what about
which message. What travels is a payload with the message's ID, the emoji, and
the time it was chosen (`Payload.Reaction`). Only emoji from a fixed list travel
— the six on the bar and the twelve behind `+` — so a reaction cannot be used to
send text past the reader's expectations, and a character a newer Maun knows is
dropped by an older one instead of being shown as something else.

Storage is one row per message and side (`reactions`, primary key on
`message_id` and `mine`), so each side has at most one emoji on a message and a
second choice replaces the first. **The time it was chosen decides, not the time
it arrived**: a phone that reconnects sends what it has in its own order, so a
reaction chosen a second earlier can arrive a second later. `ChatEngine` keeps a
reaction only when its time is later than the one already stored, which makes
the two phones agree whichever order the relay delivers in. Taking a reaction
off travels as an empty emoji and deletes the row; it is subject to the same
rule, so an old "take it off" cannot remove a newer mark.

Deleting a message deletes the reactions on it, forgetting or blocking someone
deletes theirs, and clearing every message clears the table: nothing is left
describing something that is not there.

## Replies (Round 10)

`messages.reply_to` holds the ID of the message an answer answers, nothing more
(database version 7, Room's own migration from 6). A reply whose quoted message
this phone never received still arrives and simply stands on its own; a quote
whose ID is not a message ID is dropped at the payload, and the text arrives
without it. Deleting the quoted message leaves the answer without a quote rather
than removing the answer.

The database is version 6. The migration from 5 is Room's own: one new table and
one new column (`outbox.wakes`, which says that an entry with no message of its
own — a reaction — should still wake the other phone, unlike a receipt).

## Scrolling (Round 10)

Three things were making a long conversation heavy, and all three are fixed:

* **Rows could never be skipped.** `FileActions` carried every callback the
  file and voice bubbles need. It was rebuilt on every recomposition of the
  screen, and being part of every row's parameters it made every visible row
  redraw itself for the mere fact that the screen above it recomposed. It is
  now `@Immutable` with `equals` over what a row actually draws — the transfers
  under way, the two messages to be said once, and the voice state — so a row
  that has nothing new to show is left alone.
* **Item building churned.** Every change to a conversation rebuilt its items
  and, with them, a `ZonedDateTime` per message and a parse of every file
  record. Both are now kept in small bounded caches, so scrolling through a
  thousand messages does not allocate a date for each of them again.
* **Smaller things.** The wallpaper's shader is made once instead of on every
  draw, and the time and date formatters are made once per language instead of
  once per bubble.

What is still not measured on a device is in `docs/PERFORMANCE.md`.

## The look, before release (Round 11)

The login screen is a mark, a welcome, one address and one button. What the
service keeps, and who can read what, is still said in full — one tap away
under **Privacy**, and reachable again from Settings under About. The name of
the server Maun talks to is not shown anywhere a member can see it: not on the
login screen, not in Settings, not on a profile or a signed-out screen.

Settings keeps every switch it had, in the same order of importance, under
seven headings with room between them: Profile, Notifications, Calls, Chats and
media, Message sounds, Privacy, About. The address and username block that used
to sit under About is gone — it belongs to Your Profile, and that is where it
lives. About is the app's name, its version, whether it is connected, the setup
check and the privacy note, and nothing else. Every switch, choice and link is
the same one, wired to the same setting as before.

The selection asked for a bar and got a small page of it: the count in a column
of its own, and every action spelled out, which on a narrow phone pushed the
last of them off the edge. It is now the conversation's header turned into a
toolbar — close, one number, and the actions as icons, scrolled only if the
phone is narrower than five of them. The five emoji moved to the message they
would go on: the bar hangs above that bubble and travels with it, so it is
never somewhere else on the screen than the message it reacts to.

The jump from a notification was counting the wrong things: it took the
message's place among the messages, while the list is numbered by items, and
the day pills are items too. Every message under the first day pill was
therefore opened one line too early, and the jump was animated, which looked
like a slow drift on a long conversation. It now counts in items, lands
immediately, lights the bubble it landed on for a moment, and pages older
history in until it finds the message; if it is not there at all, the newest
messages are shown.

## Opening at a message (Round 10)

A notification about one conversation now names the newest message it is about
(`Announcement.message`, taken from the message's own ID in `ChatSummary`), and
tapping it opens that conversation and scrolls to that message. A notification
about several conversations names none — there is no single place to go. The
request is used once and then put down, so the list does not jump again later
while the reader is looking at something else; a message older than what the
conversation has loaded cannot be reached without loading history up to it, so
in that case the conversation opens at its newest message instead.

## What is not built yet

Nothing from the eight-step plan is left except its report: forwarding covers
words only, and a forwarded message is not marked as one.
