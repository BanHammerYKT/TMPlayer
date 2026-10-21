# TMPlayer Web: device test plan

The pass a person does on real devices before the web app ships, iPhone first (docs/WEB.md,
"iPhone first"). Headless Chromium on Linux covers layout, the Service Worker and both player
engines on generated files (`web/player/test/`), but not Safari's codecs, its memory limits,
the Home Screen web app, AirPlay or the lock screen. Those need the devices below.

## Serving the build to a phone

A phone needs HTTPS for a Service Worker, OPFS and installing the app. From the worktree:

```sh
./gradlew :web:wasmJsBrowserDistribution --max-workers=2
node web/tools/serve.mjs web/build/dist/wasmJs/productionExecutable --port 8090
```

Then either:

- **A quick tunnel, no account and no certificate to install** (Cloudflare's free quick tunnels):
  `cloudflared tunnel --url http://127.0.0.1:8090` prints an `https://<words>.trycloudflare.com`
  address. Open it on the phone. Only the static files go through the tunnel; the phone talks to
  Telegram directly. The address works only while the command runs; stop it with Ctrl+C when done.
- **Or on the local network** with a certificate the phone is told to trust: make a local CA and a
  certificate for this computer's address (`mkcert`, or `openssl`), serve with
  `node web/tools/serve.mjs ... --host 0.0.0.0 --cert cert.pem --key key.pem`, and on the iPhone
  install the CA profile, then switch it on in Settings, General, About, Certificate Trust Settings.

Sign in once per device and browser (the session lives in that browser's storage). Use a chat
with real films and series in several formats.

## Devices

- iPhone on iOS 18.2 or later (the floor: WebAssembly GC). Safari tab, then installed to the Home
  Screen. iOS 26 if available.
- An Android phone in Chrome, as a tab and installed.
- A desktop with Chrome or Edge, Safari on a Mac, and Firefox.
- An iPad, for the layout and input split (Split View, a keyboard or trackpad).

## The pass

Record for each: pass, fail (what happened), or not possible. Times are from the press.

| # | What | How | Expect |
| --- | --- | --- | --- |
| 1 | Install | iPhone: Share, Add to Home Screen. Android and desktop Chrome: the Install offer | Opens full screen with its icon; status bar and notch clear of the content; dark and light both readable |
| 2 | Sign in by QR | Scan from another phone's Telegram (Settings, Devices, Link Desktop Device) | Lands on the chat list; the session shows in Telegram as "Web (Safari on iPhone)" or similar |
| 3 | Sign in by phone | On a device without the other phone: number, code, password if set | Lands on the chat list |
| 4 | Layout by size | Rotate the phone; on the iPad use Split View and Slide Over | Bottom bar under 600 dp, rail from 600 dp; nothing lost on rotation |
| 5 | Input by pointer | iPad with and without a trackpad | Touch targets and gestures always; hover and shortcuts with the trackpad |
| 6 | MP4, H.264 + AAC | Play, seek to 50 % and 95 % | Safari's own player; first frame under about 5 s; seeks under about 3 s |
| 7 | MP4, HEVC (x265) | Same | iPhone plays it natively; Chrome on desktop falls to the decoder if it has no HEVC |
| 8 | MP4 with AC-3 or E-AC-3 sound | Same, listen | Sound on Apple devices natively; elsewhere the decoder takes over within about 4 s |
| 9 | MKV, H.264 + AAC | Same | Plays (the decoder on the canvas today; Safari's player once the repack lands) |
| 10 | MKV with HEVC 10 bit 1080p | Same | Plays on hardware, or says plainly that it needs the app, with Play anyway |
| 11 | MKV with DTS or TrueHD | Same, listen | Sound through the decoder |
| 12 | Big file, seek to the end | A 3 to 4 GB MKV, seek to 95 % | Plays from there within a few seconds; the tab is not reloaded by iOS; memory steady (Safari's Web Inspector over USB, Timelines, Memory) |
| 13 | Subtitles | A file with SRT, one with ASS, one with PGS | SRT and ASS shown (ASS as plain lines for now); PGS listed and labelled, never "no subtitles" |
| 14 | Audio tracks | A file with two languages | Switching keeps the place, sound back within about half a second |
| 15 | Picture in picture | The player's button | Safari tab: works. Home Screen app on iOS: the button is not there (WebKit bug 303885) |
| 16 | AirPlay | From Safari's player on an iPhone, an Apple TV on the network | Plays on the TV |
| 17 | Lock screen | Play, lock the iPhone | Sound continues; title and buttons on the lock screen; play, pause and skip work |
| 18 | Background and return | Play, switch apps for a minute, come back | Continues or resumes quietly where it was; no sign in again |
| 19 | Low memory | Play several large videos in a row, then open the camera and come back | The app reloads at worst, keeps the session and the place |
| 20 | Offline | Airplane mode, open the installed app | Opens, says it is offline; a video played earlier plays from storage |
| 21 | Another tab | Open the app in a second tab | The first says TMPlayer is open in another tab, with Use it here |
| 22 | Keyboard (desktop) | Space, arrows, J and L, M, F, digits, V, A, Z and X, ? | As on the desktop app |
| 23 | Gestures (phone) | Double tap the sides, hold, swipe up and down on each side, swipe sideways | Seek 10 s per tap, 2x while held, volume (not on iOS) and brightness, scrub |
| 24 | Storage | Settings, then after a week of not opening the installed app | Still signed in (Home Screen apps keep their storage) |

## Reporting

Note the device, the iOS or browser version, installed or tab, and for a failure a screen
recording if possible. No account data, chat names or file names in a report that leaves the
device: describe files by format and size.
