# Project Overview: Zero-Tracking Private Messenger

## What We Are Building
A simple, fast, private messaging app—similar to WhatsApp—built from the ground up with **zero tracking**. There are no ads, no analytics, no phone number sign-ups, and no chat history stored on any central server.

## Core Features
1. **Text Messaging**
   - Instant one-on-one encrypted messaging.
   - Delivery indicators (Sent, Delivered, Read) without public "last seen" or online status surveillance.
2. **Voice Messages**
   - Lightweight voice notes that play smoothly even on slow connections. Press
     the microphone once to start and once to stop; the recording can be paused,
     thrown away or sent (it is tap-then-tap, not hold-to-talk).
3. **Voice & Video Calls**
   - Encrypted one-on-one audio and video calls with IP-address protection so callers cannot see each other's physical location/IP.
4. **Capped File Sharing**
   - Send documents, images, and files up to a strict size limit (e.g., 25 MB per file).
   - Shared files automatically self-destruct from the relay server after 24 hours.
5. **Private Identity (No Phone Numbers)**
   - Users connect using a generated private ID or username—no SIM card and no
     phone number. An open relay asks for no email either. A member-only relay
     (an option an operator can turn on) does: that is how it decides who may
     join, and the address is used for nothing else.

## What We Are Intentionally Leaving Out
- No status updates / stories
- No channels, communities, or public discovery
- No contact list uploading or address-book scraping
- No cloud chat backups on our servers (your messages stay strictly on your phone)