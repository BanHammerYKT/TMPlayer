# Flathub

Decision: **no Flathub package for now.** Linux users have the AppImage, the tarball and the AUR
package. Not blocked by policy, but by effort: Flathub needs TMPlayer built entirely from source
inside flatpak-builder, offline, and the desktop app depends on three prebuilt native stacks that
would each have to be rebuilt there.

## What happened

- TMPlayer shipped its own Flatpak from 1.18.0 to 1.21.0 (`desktop/packaging/flatpak/`, added in
  99e6291). It was dropped in 882ef89 (2026-10-04) with the deb, rpm, per-ABI APKs and the Windows
  zip, to keep each release to a few files. `INSTALL.md` moves Flatpak users to the AppImage.
- A submission, flathub/flathub#10513, was opened on 2026-10-05 and closed by the submission bot
  the same day (checklist format). The reviewer also noted that the linked demo video was not
  recorded on Linux, and its manifest unpacked the prebuilt `v1.22.0` tarball, which Flathub does
  not accept. It was left closed.

## The rule that blocks it

From Flathub's requirements (docs.flathub.org, "Building from source"):

> All source available submissions must be built entirely from source code. This requirement
> applies to the main application component defined in the manifest, as well as any runtime
> dependencies included in the manifest.

and "There is no network access during the build process", so every Gradle and Maven dependency
must be listed in the manifest as a source.

| Piece | Today | What Flathub needs |
| --- | --- | --- |
| Kotlin, Compose Desktop, all jars | Gradle downloads them | Offline Gradle build: a sources list from flatpak-builder-tools' Gradle generator, `org.freedesktop.Sdk.Extension.openjdk21` for the JDK, the list kept current on every dependency change |
| TDLib | Prebuilt `.so` inside the `tdl-coroutines` jar | Build TDLib (C++, CMake, OpenSSL, zlib, gperf) from source as a module, and make the JNI loader use it |
| libmpv and FFmpeg | Prebuilt inside the `mediamp-mpv-runtime-linux-x64` jar | Build mpv and FFmpeg from source (Flathub has shared modules), and point mediamp at them |
| Skiko (Compose's renderer) | Prebuilt `.so` inside the skiko jar | An exception, or Skia and Skiko from source, which is very heavy |

Flathub grants exceptions "to well-known vendors on a case-by-case basis", which does not cover a
one person project. Realistic effort: several days for a first working manifest, then upkeep on
every release and dependency bump. Revisit only if Linux users who cannot use the AppImage ask.

## If it is ever revisited

- **App ID:** keep `io.github.dracu_lah.TMPlayer`, which the metainfo, desktop file, icons and the
  old Flatpak already use; verification is by signing in to Flathub with the GitHub account.
- **Starting point:** the old Flathub manifest's finish-args (network, ipc, x11, pulseaudio, dri,
  `xdg-download:create`, notifications, screensaver inhibit, MPRIS names):
  `git show 882ef89^:desktop/packaging/flatpak/io.github.dracu_lah.TMPlayer.flathub.yml`.
  Its build section (the prebuilt tarball) has to be replaced.
- **Metainfo:** `desktop/packaging/linux/io.github.dracu_lah.TMPlayer.metainfo.xml` has a
  `<screenshots>` block, but it points at `main` and shows the TV app. Flathub wants desktop
  window shots, pinned to a tag, preferably PNG.
- **Self update:** `SelfUpdate.kt` already stands aside inside a Flatpak (`/.flatpak-info`,
  `FLATPAK_ID`).
- **A Linux recording** of the Flatpak itself for the PR, and an honest answer to the PR
  template's question on AI tools.
- Submission follows Flathub's docs: a PR against the `new-pr` branch of flathub/flathub, titled
  `Add <app-id>`, built and linted locally with `flathub-build` and `flatpak-builder-lint` first.
