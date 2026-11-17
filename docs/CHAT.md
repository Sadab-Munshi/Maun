# The conversation screen: selecting, and reactions

## What a long press does now (Round 10)

Before, a long press on a bubble opened a floating popup with Copy, Save and
Delete, one message at a time. That popup is gone. A long press now starts a
selection, and the composer's place at the bottom of the screen becomes the
bar that does something with it:

* **The emoji bar**, when exactly one message is selected: ❤️ 😂 😮 😢 👍 🙏 and
  a button for a longer list. The emoji this phone already put on the message
  is marked, and tapping it again takes it off.
* **Copy**, in time order, oldest first, for every text message selected — one
  message or ten, in the order they were written rather than the order the list
  shows them.
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

The database is version 6. The migration from 5 is Room's own: one new table and
one new column (`outbox.wakes`, which says that an entry with no message of its
own — a reaction — should still wake the other phone, unlike a receipt).

## What is not built yet

Reply, forward and share from the selection bar, and opening a conversation at a
particular message. They are the next steps of the same plan.
