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

## Later

- Overlay mode (take over the cover screen when it wakes, like CoverScreen OS)
- More Omarchy themes (Gruvbox, Kanagawa, Matte Black…)

Font: JetBrains Mono, SIL Open Font License 1.1 (see `FONT-LICENSE.txt`).
