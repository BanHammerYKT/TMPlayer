# Device pass

One pass over every target before a batch of changes is committed. Order: a connected phone, then
the phone and TV emulators, then stop them and do the Windows 11 VM. One emulator set or VM at a
time. Fix what is confusing before committing, not after.

Automated parts: `flows_phone.py`, `flows_tv.py` (each writes screenshots and a `REPORT.md`; a
missing control is a finding, not a crash). Everything marked **[hand]** needs eyes. Screenshots
go outside the repository (`--out <folder>`).

Each pass adds its own section of checks for the features in the batch, under P, T and W below.
The sections here are what every pass repeats.

Test chats: pick ones that cover a video posted several times (copies), a show with several
seasons, x265 MKV episodes, and a forum chat with saving off. Pass them with `--chat` and a
search text that hits a repeated video with `--query`.

## 0. Prepare

1. Builds with JDK 21 (see [docs/BUILDING.md](../../docs/BUILDING.md)): unit tests green for
   core, ui, app and desktop; `:app:assembleRelease` for a real phone, `:app:assembleDebug` for
   the emulators, the keyed desktop jars, and a CI run for the MSI.
2. Check the APK is really new (a stale versionCode has fooled us before):
   `$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep ^package`
3. The writing rules (no em or en dashes) and their grep are in [AGENTS.md](../../AGENTS.md).
4. **Data baseline [hand], on each target before installing the new build:** Settings, Backup,
   Back up to a file and keep it. Write down: the Continue watching list (count, and one show with
   several episodes started), two resume points (video and minute), Watch later count, favourites
   count, one download that exists in two copies (see D3), Remove after watching on or off.

## P. A real phone, release build (portrait AND landscape on every screen)

Ask before using a phone someone else shares, and stop at once if they need it. Release over
release keeps the sign in; never a debug APK here.

```sh
adb devices -l                                   # the phone must say "device"
CONFIRM_REAL=1 scripts/devicepass/emulators.sh install <serial> app/build/outputs/apk/release/app-arm64-v8a-release.apk
python3 -I scripts/devicepass/flows_phone.py --serial <serial> --real-device \
    --out <folder>/phone --query "<search text>" --chat <chat>
```

The script restores auto rotate at the end (`wm user-rotation free`, accelerometer_rotation 1).
Then by hand, each in portrait and landscape, the batch's own checks, and:

**Rotation and RTL sweep [hand].** Rotate on every screen the batch touched, with dialogs, menus
and the keyboard open. Nothing resets, closes or loses its scroll. One quick look in Arabic (RTL):
bar order, chevrons, floating buttons on the correct side.

## E. Phone emulator `tmplayer_phone_api36`, debug build

```sh
scripts/devicepass/emulators.sh start phone        # runs in the foreground: start it in the background
scripts/devicepass/emulators.sh wait phone
scripts/devicepass/emulators.sh install phone app/build/outputs/apk/debug/app-universal-debug.apk
python3 -I scripts/devicepass/flows_phone.py --serial emulator-5554 --out <folder>/phone-avd
```

- The emulator should be signed in already. If it asks to sign in, stop and get the QR scanned
  before testing anything else.
- The `offline` flow runs here only (airplane mode on, search, off).
- Repeat the phone checks quickly where the real phone lacked data; a cold start with
  `-no-snapshot-load` doubles as the "fresh process" check for anything remembered.

## T. TV emulator `tmplayer_tv_api36`, debug build (never the real stick)

```sh
scripts/devicepass/emulators.sh start tv           # runs in the foreground: start it in the background
scripts/devicepass/emulators.sh wait tv
scripts/devicepass/emulators.sh install tv app/build/outputs/apk/debug/app-universal-debug.apk
python3 -I scripts/devicepass/flows_tv.py --serial emulator-5556 --out <folder>/tv
```

- Focus starts on content, Left reaches the rail, Back from the rail goes to Home and then asks
  to leave. Focus never lands on an invisible item, and comes back where it was after a dialog.
- A focus ring is visible on every card; holding OK opens the card's menu.
- Long lists: holding Down scrolls smoothly.
- Confusing UX: focus jumping to the rail after closing a dialog; a menu unreachable with the
  D-pad; text cut in the 1 GB AVD's small font scale.

Then: `scripts/devicepass/emulators.sh stop all` (stops only what the script started).

## W. Windows 11 VM, MSI (after the emulators are stopped)

```sh
scripts/devicepass/vm.sh start
scripts/devicepass/vm.sh fetch-msi <ci run id>     # or copy-msi <file.msi>
scripts/devicepass/vm.sh copy-jars                 # keyed local jars, so sign in works
scripts/devicepass/vm.sh queue-install --jars      # upgrade in place; --clean for a fresh install
```

Inside the VM (http://127.0.0.1:8006), if the runner is not running:
`powershell -executionpolicy bypass -file \\host.lan\Data\runner.ps1`. Output lands in
`$VM_SHARED/q/NN-devicepass-install.out` (default `~/vms/win11/shared`).

- W1. Upgrade in place from the previous MSI: still signed in, settings, favourites, resume points
  kept (the 1.26.0 MSI update used to delete them: issue #7). Installed under
  `%LOCALAPPDATA%\TMPlayer App`.
- W2. The batch's screens with mouse and keyboard (right click menus, shortcuts).
- W3. Dark theme, window resize to narrow and wide, 125 % and 150 % scaling.
- W4. Fullscreen on Windows 11. CI checks it on a Windows Server runner (one monitor, no Windows 11
  taskbar), so these still want a hand:
  - [ ] Maximized, **F**, **F**: the window comes back maximized, with the taskbar visible below it.
  - [ ] Not maximized, **F**, **F**: the window comes back to the same size and place.
  - [ ] The same two with the taskbar at the top, and on the left.
  - [ ] With the taskbar set to hide automatically, it still pops up over the restored window.
  - [ ] A second monitor at a different scale: fullscreen fills that monitor, and back lands there.
  - [ ] **Win+Up** while fullscreen does not leave fullscreen half way; **F** then comes back maximized.
  - [ ] Minimize from the taskbar while fullscreen, then restore: still fullscreen, and **F** comes back as before.
- Then `scripts/devicepass/vm.sh stop` (only if this script started it).

## D. Data safety (every target where it applies)

- D1. **Continue watching does not collapse a show.** Start S01E02 and S01E05 of one show, and a
  video with the same words in its name. Continue watching keeps the show as one card at the
  latest episode and the other video as its own card; grouping by copies must never merge two
  different episodes or the show with the other video. Compare with the baseline list.
- D2. **Progress carries across copies.** Stop copy A at a known minute; open copy B (another chat):
  resume offered at the same minute; finish B: the video is watched everywhere, and A's card shows
  watched too. A copy a few per cent longer still carries; a different cut does not.
- D3. **Remove after watching deletes the right file.** With Remove after watching on and copy A
  downloaded, watch copy B to the end (streamed): A stays in Downloads; watch downloaded A to the
  end: A goes, B is untouched, nothing else in Downloads changes. Check the Downloads folder on
  disk too.
- D4. **Corrections stay put.** Not the same video, Wrong match and Rename survive restart, a chat
  refresh and an app update; undo works.
- D5. **Backup and restore.** Back up after changing the settings the batch added. Restore on a
  fresh AVD user or after clearing them: the restore summary lists them, and each is back. A
  backup from the previous release still restores.
- D6. **No crash or ANR.** The flows check logcat for FATAL EXCEPTION and ANR in com.tmplayer after
  every step; at the end also `adb -s <serial> logcat -d -b crash`.

## UX watch list (note each, fix before commit)

- Labels that only make sense to us.
- Dead ends: a page with no way back except system Back; Back that leaves the app from a sub page.
- Surprising jumps: lists reordering under the finger, results shifting, the lit tab not matching
  the page.
- Two cards for one video, one card for two videos.
- Anything hidden by the floating bar, the keyboard, or a floating button.
- Cut text in German or Arabic, RTL mirror mistakes.

## Sign off

All flows' REPORT.md findings triaged, every [hand] item seen in both orientations on the phone,
TV and desktop items done, D1 to D6 pass, emulators and the VM stopped. Then commit.
