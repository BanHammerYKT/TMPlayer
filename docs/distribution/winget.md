# winget (Windows Package Manager)

Status: **not on winget yet.** The first version has to be submitted by hand (U13, on Windows).
After that the release job sends every new version by itself. The hand-written manifests in
[winget/](winget/) are from 1.24.0 and must be regenerated for the current release at submission.

`winget install TMPlayer.TMPlayer` matters because winget fetches the MSI itself, so the file has no
browser download mark and SmartScreen does not stop the unsigned installer.

## The package

A pull request to [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) that adds:

```
manifests/t/TMPlayer/TMPlayer/<version>/TMPlayer.TMPlayer.yaml
manifests/t/TMPlayer/TMPlayer/<version>/TMPlayer.TMPlayer.installer.yaml
manifests/t/TMPlayer/TMPlayer/<version>/TMPlayer.TMPlayer.locale.en-US.yaml
```

- **PackageIdentifier `TMPlayer.TMPlayer`.** winget's convention is `Publisher.Package`, and the
  publisher matches the MSI's Manufacturer (`vendor = "TMPlayer"`), which is what Apps and Features
  shows. The identifier is permanent once merged, and the release job already uses it.
- **Schema 1.12.0**, the current one, which `wingetcreate` also writes.
- **PrivacyUrl** is `https://tmplayer.org/privacy/`, in the locale file next to the licence.
- **InstallerType `msi`, Scope `user`.** The MSI is made by jpackage (`packageReleaseMsi`) from the
  WiX template in `desktop/packaging/windows/main.wxs`, with `perUserInstall = true`, so it needs
  no administrator. jpackage MSIs take `/quiet`, so winget's silent install works.
- **ProductCode** changes with every version (jpackage derives a new one), so it is read from the
  MSI being submitted. The **UpgradeCode `{6F1D3C0E-2B8A-4B7E-9D2C-7A1E5F4C3B21}`** is fixed
  (`upgradeUuid` in `desktop/build.gradle.kts`).
- **InstallerSha256** is the hash in `site/public/latest.json`, upper case as winget writes it.

To read the ProductCode yourself: `msiinfo export TMPlayer-<v>-windows-x64.msi Property | grep
ProductCode` (msitools, Linux), or let `wingetcreate` fill it in.

## First submission (U13, by hand)

On Windows, with the MSI of the current release:

```powershell
winget install Microsoft.WingetCreate
wingetcreate token --store
wingetcreate new https://github.com/dracu-lah/TMPlayer/releases/download/v<v>/TMPlayer-<v>-windows-x64.msi
```

`wingetcreate new` reads the MSI (ProductCode, UpgradeCode, Scope, hash) and asks for the rest:
identifier `TMPlayer.TMPlayer`, publisher `TMPlayer`, name, licence and description, whose answers
are in the locale file in [winget/](winget/). It has no `--submit` flag: it asks at the end, then
forks winget-pkgs and opens the PR. To submit corrected hand-written files instead:
`wingetcreate submit --prtitle "New package: TMPlayer.TMPlayer version <v>" <folder>`.

Optional local check first: `winget validate --manifest <folder>` and `winget install --manifest
<folder>` (needs `winget settings --enable LocalManifestFiles` from an elevated prompt).

Then sign the CLA on the PR (`@microsoft-github-policy-service agree`) and answer any
Needs-Author-Feedback label within 5 days. Being unsigned is not a blocker, but a new unsigned MSI
is sometimes held for manual review. If a "Telegram" trademark question comes up: the name has no
"Telegram" in it, the icon is TMPlayer's own, the description says it is unofficial and not
affiliated, and the package installs the MSI from TMPlayer's own GitHub release. Never replace a
release file after submitting.

## Every release after that

The `winget` job in `.github/workflows/release.yml` runs after the release is published, using
`vedantmgoyal9/winget-releaser@v2` with `identifier: TMPlayer.TMPlayer`. It reads the new MSI, so
the ProductCode and hash fill themselves in, and opens the PR from the token owner's fork of
winget-pkgs.

- It needs the `WINGET_TOKEN` repository secret: a classic personal access token with
  `public_repo`, and the fork has to exist. Without the secret the job skips itself.
- It only updates a package that already exists, so it fails until the first submission above is
  merged. It is `continue-on-error`, so it never fails the release.
- It skips a tag that is not the latest release, so winget never moves backwards.

winget and the app's own updater both offer updates. That is harmless (the MSI upgrade replaces
the old copy either way), but winget only sees a new version once its PR is merged, usually a day
or so later.
