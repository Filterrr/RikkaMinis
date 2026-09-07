# MinisApp shell configuration
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# Locale: Ubuntu base ships only the C.utf8 locale — declare it explicitly so
# glibc programs stop warning about an unset LANG.
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# T294: prompt parity with iOS — `root@minis:/var/minis#`. iOS bakes the
# literal "minis" into PS1 rather than relying on \h, so the prompt is stable
# regardless of what /etc/hostname happens to contain. We do the same here so
# a fresh install matches without needing a rootfs rebuild.
export PS1='\u@minis:\w\$ '

# Command history for login shells (interactive bash also sources ~/.bashrc,
# overlaid from default_mount/root/.bashrc).
export HISTFILE="$HOME/.bash_history"
export HISTSIZE=1000

# BASH_ENV: non-interactive bash (`bash -c`, which is what agent shells use)
# sources this file — the dash equivalent of the old ash ENV mechanism. It
# picks up the shipped aliases and prompt on non-login shells.
export BASH_ENV="$HOME/.bashrc"

# Default pager — less is NOT in the Ubuntu base rootfs; more is installed.
# Keep explicit for scripts that probe $PAGER.
export PAGER=more

# URL interception: $BROWSER is also seeded directly into every process
# envp via PRootKernel.customEnvironment so non-login shells (which never
# source profile.d) still see it. The xdg-open / sensible-browser /
# www-browser / x-www-browser / gnome-open / kde-open wrappers live as
# real files in default_mount/usr/local/bin/ and are overlaid on every
# boot.
export BROWSER=/usr/local/bin/minis-open

# T222: PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# every hardlinked file. uv's default `hardlink` mode tries to re-link these
# sentinels when populating site-packages, which PRoot rejects with EPERM.
# Force uv to symlink package files instead — the sentinels are then never
# touched as link sources. Reported as openminis/openminis#7.
export UV_LINK_MODE=symlink

# Debian policy: never let apt/dpkg start daemons inside the sandbox —
# there is no init system under PRoot, and maintainer scripts that try
# (invoke-rc.d / systemctl) would block or fail noisily. The stub at
# /usr/sbin/policy-rc.d (exit 101) is the same mechanism Termux's
# proot-distro uses.
export DEBIAN_FRONTEND=noninteractive

# T-fix-tmpdir-leak: pin the POSIX temp dirs to the guest /tmp. Login shells
# inherit the host app process env through proot, where TMPDIR points at an
# Android app-cache path that does not exist inside the rootfs — Debian
# maintainer scripts (update-ca-certificates' `mktemp -p "${TMPDIR:-/tmp}"`
# under set -e) and anything else honoring TMPDIR then fails with
# "No such file or directory". PRootKernel.customEnvironment seeds the same
# values for non-login shells; this export covers interactive logins even if
# a user-level env var overrides the seeded map.
export TMPDIR=/tmp
export TMP=/tmp
export TEMP=/tmp
export TEMPDIR=/tmp
