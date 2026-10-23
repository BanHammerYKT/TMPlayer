# AUR: tmplayer-bin

The Arch Linux package is `desktop/packaging/aur/PKGBUILD`, a `-bin` package over the release's
`TMPlayer-<v>-linux-x64.tar.xz`. Each release moves it on its own: the release job's feed step runs
`scripts/aur-bump.py` (version, `pkgrel=1`, the tarball's SHA-256) in the same commit as
`latest.json`, and the step after it, "Publish the AUR package", pushes it to the AUR with
`KSXGitHub/github-actions-deploy-aur`, which writes `.SRCINFO` itself.

Until the secret below exists, the package is not on the AUR at all, which is why searching
aur.archlinux.org for "tmplayer" finds nothing, and the publish step skips itself.

## One-time setup (the maintainer)

1. Create an account at https://aur.archlinux.org/register.
2. Make a key used only for the AUR, without a passphrase (the release job cannot type one):

   ```sh
   ssh-keygen -t ed25519 -f ~/.ssh/aur -C aur -N ''
   ```

3. In the AUR account settings ("My Account"), paste `~/.ssh/aur.pub` into "SSH Public Key" and save.
4. Create the package with a first push. The name is claimed by whoever pushes first:

   ```sh
   git -c core.sshCommand='ssh -i ~/.ssh/aur' clone ssh://aur@aur.archlinux.org/tmplayer-bin.git
   cd tmplayer-bin
   cp ../TMPlayer/desktop/packaging/aur/PKGBUILD .
   makepkg --printsrcinfo > .SRCINFO
   git add PKGBUILD .SRCINFO
   git commit -m "Initial upload: tmplayer-bin <current version>"
   git -c core.sshCommand='ssh -i ~/.ssh/aur' push origin master
   ```

   `makepkg` needs an Arch system or container (`podman run --rm -it -v "$PWD:/pkg" -w /pkg
   archlinux bash`, then `pacman -Sy --noconfirm base-devel` and run it as a normal user).
5. Give the release job the key, as a repository secret (the publish job uses repository
   secrets, not the `release` environment):

   ```sh
   gh secret set AUR_SSH_PRIVATE_KEY < ~/.ssh/aur
   ```

From the next release on, nothing is done by hand. To push a fix to the package between releases,
bump `pkgrel` in the repository's PKGBUILD and push it to the AUR the same way as step 4.
