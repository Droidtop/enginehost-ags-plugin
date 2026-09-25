# Third-party components

This branch (`plugin-core`) carries Enginehost's AGS wrapper only. The AGS
engine and its Android port are merged in per release line
(`plugin/3.6.3.15`), which starts at the upstream release tag.
`enginehost/LICENSES.md` documents the notices that ship inside the built
bundle and is authoritative for what is distributed; this table indexes it.

| Component | Version | Licence | Source | Where in tree |
|---|---|---|---|---|
| Adventure Game Studio | the tag each `plugin/<line>` starts at (3.6.3.15) | Artistic-2.0 | https://github.com/adventuregamestudio/ags | the line branches |
| SDL2, SDL_sound, libogg, libvorbis, libtheora | the revisions AGS's `CMake/Fetch*.cmake` pin | zlib, BSD-3-Clause | their upstreams | fetched at build time, built into the runtime libraries |
