# Enginehost AGS plugin

This repository packages the official Adventure Game Studio engine, through its
own Android port, as an Enginehost engine bundle, so AGS games run in place
from wherever they are, with no copy and no per-game APK.

## Branch model

`master` follows upstream AGS. `plugin-core` is the Enginehost changeset alone:
the wrapper activity, the build workflow and the bundle metadata. Release lines
(`plugin/3.6.3.15`) start at an upstream release tag, merge `plugin-core`, and
carry the few seams the wrapper needs in the engine's own files (below). Every
push to a line builds, signs and publishes the bundle on the unstable channel;
promotion to testing or stable is a manual dispatch.

## What the wrapper does

`EngineHostAgsActivity` takes Enginehost's runtime contract and gives the
engine the game's main data file as its command-line game path: the `execFile`
Enginehost detected (a 3.5+ `.ags`, or the `.exe` an older game carries its
data in), else the file the engine itself would pick. The engine's own global
config and log go to a folder of the runtime's; the game folder is read, never
written, by the wrapper.

The seams on a line branch:

- `Engine/platform/android/acpland.cpp` reads `ENGINEHOST_AGS_SAVE_ROOT`. On the
  desktop platforms AGS saves in the system's saved-games folder, in a
  subfolder the game names (`saveGameFolderName`), and keeps shared data in an
  all-users folder (the XDG platforms: `$XDG_DATA_HOME/ags` and `ags-common`).
  The upstream Android port writes into the game folder instead. Enginehost does
  not change where a game saves; it makes that system folder mean the save
  folder it hands the runtime, and the game still names its own subfolder there.
- Gradle: arm64-v8a and x86_64 (every Enginehost bundle carries both), the
  resource table at package id 0x80 (Enginehost's own is 0x7f), no
  minification of the runtime library (the engine reads its Java fields by
  name), and the plugin's own application id.

## Controller

AGS has no pad model of its own: games read the mouse and the keyboard.
Enginehost's map for `ags` is AGS's own inputs under the engine's own names
(`eMouseLeft`, `eMouseRight`, the wheel, `eKeyEscape`, `eKeyReturn`, the arrow
keys, F5 and F7 that the stock templates save and restore with), and the
wrapper turns each bound pad control into that mouse button, wheel step, key,
or, for the stick, pointer movement. Touch keeps the upstream port's
touch-to-mouse emulation.

Upstream: https://github.com/adventuregamestudio/ags.
Enginehost: https://github.com/Droidtop/enginehost.
