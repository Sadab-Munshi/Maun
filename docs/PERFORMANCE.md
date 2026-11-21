# Where the conversation screen's work goes

Written before changing anything, as the plan asks. Nothing here is a measured
device profile: no phone or emulator was available, so this is what the code
does by inspection, and what would need a trace to confirm.

## Already in place, from earlier rounds

* **Paging.** A conversation loads `Messenger.MESSAGE_PAGE` messages and grows by
  a page as the reader approaches the oldest loaded one; the window survives
  turning the phone (`rememberSaveable(chatPeer)`).
* **Stable list identity.** The message list is keyed by message (`key = { it.key }`,
  day separators included) with a `contentType` per kind, so inserting a message
  does not rebuild every row.
* **Work hoisted out of drawing.** Run grouping, day labels, attachment parsing
  and link spans are computed once per message list (`chatItems`, `MessageItem.file`,
  `remember(message.text)`) rather than per frame.
* **Wallpaper.** The pattern is drawn once into a tile and repeated by the GPU;
  drawing hundreds of shapes per frame was what made long conversations scroll
  unevenly in the first place.
* **Pictures.** Decoded off the main thread, downsampled by powers of two to the
  size asked for, kept in an `LruCache` keyed by message and size, and a small
  copy is kept on disk so reopening a conversation does not decrypt the original
  again.
* **Avatars.** Their own 8 MB cache, decoded off the main thread.

## Fixed since this was written (Round 10)

`FileActions` was rebuilt on every recomposition of the conversation and was
part of every row's parameters, so a progress tick on an upload recomposed the
visible rows. It is now `@Immutable` with an `equals` over what a row actually
draws — the transfers under way, the two messages to be said once, the notice
and the voice state — while the callbacks are deliberately left out of that
comparison, because they are made again on every recomposition and comparing
them by identity is exactly what caused the redraws. The item building was
fixed in the same round: the item list's `ZonedDateTime` per message and the
parse of every file record are kept in small bounded caches instead of being
recomputed. See `ui/FileBubble.kt` (the comment above `FileActions`) and
[CHAT.md](CHAT.md) → Scrolling, which is where the reasoning lives. None of it
has been confirmed with a device trace.

## Worth knowing, and still not measured

* `observeChats` finds each conversation's newest message with a subquery
  (`SELECT MAX(seq) … GROUP BY peer`) and counts unread per conversation, on
  every message change, including a read tick. It is one query over an indexed
  table; a device trace would say whether the chat list redraw is ever the slow
  part.

## What a device trace should collect

    adb shell am start -n org.maun.messenger/.MainActivity
    adb shell atrace -t 10 -b 16000 gfx view sched freq maun > /data/local/tmp/trace
    adb shell perfetto -c /data/misc/perfetto-configs/maun.cfg -o /data/misc/perfetto-traces/maun

Scroll a 500-message conversation to the oldest loaded message and back, then
open a conversation with 20 pictures. What to look for: frames over 16 ms while
scrolling (jank), and time in `decodeStream`/`BitmapFactory` on the main thread,
which would mean a picture slipped past the IO dispatcher.
