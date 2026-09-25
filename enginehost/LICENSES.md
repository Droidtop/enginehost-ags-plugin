# Licences covering this bundle

The payload is the AGS Player Android build from this repository's
`plugin/<version>` branch (the AGS engine and its Android runtime library),
the native libraries it carries, and the Enginehost wrapper class. Each
component keeps its own licence; nothing here relicenses anything.

| Component | Licence | Notice shipped in the payload |
| --- | --- | --- |
| Adventure Game Studio engine and Android port (`https://github.com/adventuregamestudio/ags`) | Artistic-2.0 | `LICENSE.ags.txt` |
| SDL2, SDL_sound, libogg, libvorbis, libtheora (fetched and built by the repository's own CMake at the revisions it pins) | zlib, BSD-3-Clause | their upstream notices, as referenced from `LICENSE.ags.txt` and `Copyright.txt` on the line branch |
| Enginehost wrapper (`EngineHostAgsActivity`) | MIT | `LICENSE.enginehost.txt` |

The Artistic License 2.0 lets a modified version be distributed when the
modifications are made freely available; they are this repository's
`plugin/<version>` branch, which is the corresponding source of this bundle.
Every change to the engine's own files there is an Enginehost seam named in
ENGINEHOST.md.
