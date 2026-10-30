# cover

A text-only, Omarchy-style (Tokyo Night) launcher for the Galaxy Z Flip 8 cover screen.

## What's on it

| Tile | Source |
| --- | --- |
| status bar | date, time, battery · tap `● cfg` for settings |
| cal | next 3 events from every calendar on the phone (Homebase arrives via ICSx⁵) |
| todo | your + family's open Homebase tasks · tap one to complete it (repeating tasks roll forward, like in Homebase) |
| play | whatever's playing (Spotify etc.) · prev / play-pause / next |
| ask claude | quick chat · opens straight into voice input |
| launch | a–z grid with fuzzy match · auto-launches when one app is left · `⏎` opens the top match · long-press `⌫` clears |

Typing `ask` or `cover settings` in the grid works too.

## Page 2 (swipe left from page 1)

Kanagawa Dragon colours, so you can tell which page you're on at a glance. The background blends between the two as you swipe. Cover reopens on page 1 after it's been away for 30 seconds or more.

| Row | What it does |
| --- | --- |
| status | page dots (tap to go back) · weather for Spring Mountain from Open-Meteo, e.g. `24° rain from 3pm` |
| today | how many times you've unlocked the phone today, and total screen-on time |
| capture | type or tap `mic`; each entry becomes its own dated `.md` note in the inbox folder picked in cfg · start with `+` (e.g. `+milk`) to add to the shopping list instead |
| people | message notifications from people · tap for the list, reply in place or open the conversation |
| doom and friends · pay | plays the playlist (through Spotify's session if it allows, otherwise opens it) · opens Google Wallet |
| speed dial | three contacts picked in cfg; calls start straight away with the phone closed · the number is looked up when you tap, never stored |
| min 5 10 25 · shop | one-tap timers · Homebase grocery list: tap to see, tick off, or add items (typed, or by voice: "milk and eggs" adds two) |
| torch cam bt↗ wifi↗ · brief me | torch toggle · camera · Bluetooth and Wi-Fi settings (Android won't let apps flip those directly) · a three-line rundown of your day from Claude |

Set up once in `● cfg` → **page 2**: grant contacts and calls, turn on usage access for cover, pick the inbox folder, label and pick the three speed-dial contacts. People uses the media access you've already granted.

Privacy: message notifications stay in memory while cover runs and are never saved or sent. "brief me" sends your upcoming calendar titles, open todo titles, the weather line and the grocery count to Claude, nothing else.

## Install

1. On the phone, open the repo's **Releases → latest** and download `cover-launcher.apk`.
2. Allow your browser to install unknown apps when Android asks, then install.
3. Open **cover** on the main screen once and go to `● cfg`:
   - calendar → grant
   - media → open settings → turn on **cover media controls**
   - homebase → sign in with your Homebase email and password (only session tokens are stored)
   - claude → paste an Anthropic API key (stored encrypted on the phone)
4. Good Lock → MultiStar → I ♡ Galaxy Foldable → enable **cover** for the cover screen.

Every push to `main` rebuilds the APK and replaces the `latest` release. Updates install over the top because the signing key is fixed (`keys/debug.keystore`, a personal sideload key, not a Play Store key).

## Signal page (swipe right from page 1)

One Signal chat as a terminal-style log, in jade on black. Signal has no API for other apps, so the page is built from Signal's own notifications.

- **Pick the chat** in `● cfg` → signal. The list shows chats that have notified you since this update. Cover tracks the chat by its conversation ID, so group renames don't lose it; the header follows the latest name.
- **Reply** appears only while Signal has a live notification for the chat (its reply hook). Once you've read the chat elsewhere and Signal clears it, the prompt becomes **open in signal ↗** until the next message arrives.
- **Only what arrived as a notification is logged.** Nothing from before this update, and nothing you send from Signal itself. Replies sent from cover are logged.
- Signal's notifications must show name and message, or there's nothing to read.
- The log is the last 300 lines, kept in cover's encrypted storage on the phone. `clear log` in cfg wipes it.

**Over the lock screen** (cfg → signal → over lock, off by default): while the phone's locked, cover shows only this page, read-only, with an **unlock** button. Turning on "reply also while locked" lets anyone holding the phone reply as you.

## Page 4: now playing (swipe down from page 1)

Whatever's playing, with the album cover redrawn as `░▒▓█` blocks in Tokyo Night colours, on near-black. Swipe up, tap `↑ home` or press back to return. Like page 2, cover reopens on page 1 after 30 seconds away.

- **The visualiser comes from the cover, not the audio.** Each bar sits under its own two columns of the art and rests at their brightness, so the bars trace the cover's outline. Each bar takes the strongest colour above it. Dark, flat covers swell slowly; bright, busy ones move fast and jittery. The same album always moves the same way.
- It doesn't follow the beat: Spotify doesn't share its audio or tempo data with other apps. When paused, the bars settle into a faint copy of the outline.
- The art is the image the playing app gives Android for its media controls. Nothing is downloaded. If an app sends none, you get an empty grid and a plain purple pulse.
- Uses the media access you've already granted. The swipe down doesn't count if it starts at the very top edge (that's One UI's pull-down panel) or while you're typing in the launch grid.

## Page 5: out there (swipe up from page 1)

Gruvbox, four quarters. Swipe down, tap `home ↓` or press back to return. Reopens on page 1 after 30 seconds away. The swipe up doesn't count from the very bottom edge.

| Quarter | What it does |
| --- | --- |
| fuel · u91 | three nearby servos (within 10 km of home). `★` in green is the recommendation: cheapest once the drive there and back is costed (40 L fill, 8 L/100 km). `$` marks the outright cheapest when it's a different one. Tap a row to navigate there in Google Maps; tap the quarter to go to the `★`. `cycle` compares today's local prices with a few days ago (needs a few days of history first). |
| drive · work | live drive time to work and how much traffic is adding · crashes within ~300 m of the route, from QLDTraffic. Tap to navigate. |
| music ↗ | next Queensland gig by an artist you follow, and their newest release. Tap for the full music page: every gig (tap for tickets) and the last 30 days of releases (tap to play). |
| progress | day, week, month and year as filling bars. |

Fuel refreshes every 30 min, the drive every 10, music twice a day; nothing runs while page 5 isn't on screen.

**Setup** (`● cfg` → page 5 · out there; easiest on the main screen):

1. **fuel**: register as a data consumer via fuelpricesqld.com.au (free). Paste the subscriber token.
2. **tomtom**: free account at developer.tomtom.com (no card). Paste the API key. Then type your **work** address.
3. **qldtraffic**: optional. Cover uses QLDTraffic's published public key; if crashes stop showing, email qldtraffic@tmr.qld.gov.au for your own and paste it.
4. **ticketmstr**: free account at developer.ticketmaster.com. Paste the consumer key. Only Ticketmaster-sold shows appear; Oztix and Moshtix gigs won't.
5. **spotify**: create an app at developer.spotify.com (Web API), add `coverlauncher://spotify/callback` as a redirect URI, paste the client ID, then tap **connect spotify** and approve. Cover reads the artists you follow and nothing else.

Gigs use the artists you follow on Spotify, so they appear once Spotify is connected.

## Takeover (cover as the cover screen's home)

An accessibility service (`cover takeover`) brings cover back in front whenever the window you pick as Samsung's cover home appears on the cover screen. It reads only app and window-class names, never screen content, and does nothing over the lock screen.

1. App info → ⋮ → **Allow restricted settings** (Android blocks accessibility for sideloaded apps until you do this).
2. Settings → Accessibility → Installed apps → **cover takeover** → on.
3. Go to Samsung's cover home once, open cover again, `● cfg` → takeover, and tap the Samsung home line.

Cover stays off the main screen: it closes itself when unfolding carries it onto the inner display, and the takeover ignores screen 0 even if it was picked. Tapping the cover icon on the main screen on purpose still opens it, so `● cfg` is always reachable there.

Font: JetBrains Mono, SIL Open Font License 1.1 (see `FONT-LICENSE.txt`).
