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

## Worth knowing, and not changed here

* `FileActions` is rebuilt on every recomposition of the conversation, and it is
  part of every row's parameters, so a progress tick on an upload can recompose
  the visible rows. The fix is to lift the progress map out of the row
  parameters and let each row read the one value it needs; that touches the
  file and voice bubbles' whole surface, so it wants a device trace first to
  show it is the thing that hurts.
* `observeChats` recomputes the newest message per conversation with a
  correlated subquery on every message change, including a read tick. It is one
  query over an indexed table; a device trace would say whether the chat list
  redraw is ever the slow part.

## What a device trace should collect

    adb shell am start -n org.maun.messenger/.MainActivity
    adb shell atrace -t 10 -b 16000 gfx view sched freq maun > /data/local/tmp/trace
    adb shell perfetto -c /data/misc/perfetto-configs/maun.cfg -o /data/misc/perfetto-traces/maun

Scroll a 500-message conversation to the oldest loaded message and back, then
open a conversation with 20 pictures. What to look for: frames over 16 ms while
scrolling (jank), and time in `decodeStream`/`BitmapFactory` on the main thread,
which would mean a picture slipped past the IO dispatcher.
