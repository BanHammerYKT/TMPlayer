# Google Play: what a listing would take

Used by **Phase G** in the working plan (CP34 to CP36, U26 to U29), which is set aside while the
budget is $0 (U03). Nothing is submitted. Facts checked on 2026-10-19; recheck the dated ones
before acting on them.

## Why the GitHub APK cannot go to Play as it is

- **The in-app updater.** Play's Device and Network Abuse policy forbids an app from Play updating
  itself any other way than through Play, and `REQUEST_INSTALL_PACKAGES` is a restricted
  permission that needs a declared core use. A Play build drops both, the way the F-Droid build
  already does (`-PtmFdroid=true`, `BuildConfig.UPDATE_CHECK`, `src/fdroid/AndroidManifest.xml`).
- **Target API level.** Since 2026-08-31 new apps and updates on Play must target API 36 (Android
  16); an extension to 2026-11-01 can be asked for in the console. The TV-only floor (API 34) does
  not cover TMPlayer, which also ships to phones. The app targets 35 today
  (`app/build.gradle.kts`), so CP34 raises it and the player's Back handling moves to the
  predictive back API. <https://developer.android.com/google/play/requirements/target-sdk>
- **Foreground service types.** `FOREGROUND_SERVICE_DATA_SYNC` (downloads) and
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (background audio) each need a declaration in the console
  with a short video of the feature.
- **An AAB.** Play takes an Android App Bundle, so the release job builds one beside the APKs.

## The account

- A personal developer account costs a one time $25 and an identity check (U07, which also covers
  Android developer verification for the GitHub APK).
- A personal account made after 2023-11-13 must run a closed test with at least 12 testers, opted
  in for the last 14 days without a break, before production opens.
  <https://support.google.com/googleplay/android-developer/answer/14151465>
- Data safety form: nothing collected by the developer; crash reports opt-in (Sentry); the
  Telegram session stays on the device. It must match `site/src/pages/privacy/`.
- Reviewers need to get in without a Telegram code, hence CP35's demo mode with openly licensed
  clips.

## How listings like this get removed

Other players for Telegram videos have left Play, none with a published reason: TMP (Telegram
Media Player), whose developer wrote that Google removed it "for an unknown reason", Telegram Video
Player (2024-08-25) and TelePlayer (2021-09-26). In 2015 Google removed Telegram+ under its
intellectual property and impersonation rules and restored it once it said it was unofficial.
What keeps TMPlayer clear of the same:

- **Name and look.** "TMPlayer" as the title, the app's own icon, and "unofficial, not affiliated
  with Telegram" in the description (Telegram API terms 2.3 and 2.4, see
  [telegram-apps.md](telegram-apps.md)).
- **What it is for.** A player for the videos already in your own chats. The listing, the
  screenshots and the reviewer's demo show only demo or openly licensed content, never a real
  title, and the wording rules in `AGENTS.md` (video, not film or movie) apply to every field.
- **Telegram's own restrictions.** TDLib hides posts restricted for the platform unless
  `ignore_platform_restrictions` is set, and `MediaMapper` skips any message with a restriction
  reason. Never set that option in a store build.
- **A way to report.** CP35's Report action for chats, since Play treats an app that shows other
  people's posts as user generated content.

## Developer verification (also for the GitHub APK)

Since 2026-09-30 certified Android devices in Brazil, Indonesia, Singapore and Thailand install
only apps from registered developers, and Google plans the same everywhere in 2027. Unregistered
apps still install through adb or an advanced flow, which most people will not use. Registering
`com.tmplayer` and the release key (U07, U08, CP15) keeps the GitHub, Obtainium and site downloads
working, Play or no Play.
<https://android-developers.googleblog.com/2026/06/android-developer-verification.html>
