![miel — a little home for your games](assets/branding/banner.png)

# miel

An **opinionated, batteries-included, all-in-one frontend** for the dual-screen **AYN Thor**.

Pick a game, press **A**, and play. Press **Home** to save your place and return to your library.
Come back later and continue where you left off.

> [!NOTE]
> I develop miel for my own use and share it in case others find it useful. Issues and pull requests
> are disabled until I'm ready to accept outside contributions.

<p align="center">
  <img src="assets/showcase/showcase.gif" width="640" alt="miel on both Thor screens: browsing a homebrew library, opening settings and switching between one and two rows of covers">
</p>

Demo library with homebrew artwork, captured in the emulator. [Game and artwork credits](assets/showcase/CREDITS.md).

## Your games, in one place

- **Nine systems.** Play Nintendo 3DS, Nintendo DS, Game Boy Advance, Game Boy Color, Game Boy,
  NES, SNES, PlayStation and PSP games without choosing an emulator for each one.
- **A library for two screens.** Browse covers on the upper screen, with artwork and game details
  below. Choose a single row of portrait covers or two rows of square tiles.
- **Continue where you stopped.** Returning to the library keeps your place. Recent puts the games
  you last played within reach, without managing save-state slots.
- **Local files or RomM.** Choose a ROM folder, connect your self-hosted [RomM](https://romm.app)
  library, or use both. RomM games download the first time you play; in-game saves sync before
  launch and upload when you return to the library. If both copies changed, choose which to keep.
- **Automatic metadata and artwork.** RomM supplies game titles, release years and genres.
  miel downloads matching covers and screenshots from Libretro when artwork is missing.
- **Controller or touch.** Browse, launch and change settings with the Thor's controls. Touch works
  too.
- **Your Android apps, too.** Open apps on either screen. miel remembers which screen you chose.

miel embeds [Azahar](https://azahar-emu.org) for 3DS,
[melonDS DS](https://github.com/JesseTG/melonds-ds) for DS,
[mGBA](https://mgba.io) for Game Boy, Game Boy Color and Game Boy Advance,
[FCEUmm](https://github.com/libretro/libretro-fceumm) for NES,
[bsnes](https://github.com/libretro/bsnes-libretro) for SNES,
[PCSX-ReARMed](https://github.com/libretro/pcsx_rearmed) for PlayStation, and
[PPSSPP](https://www.ppsspp.org/) for PSP.

## Choose your layout

| One row of portrait covers | Two rows of covers |
| --- | --- |
| ![Single-row homebrew library](assets/showcase/library.png) | ![Two-row homebrew library](assets/showcase/grid.png) |

<details>
<summary>Settings, with controller and touch controls</summary>

<img src="assets/showcase/settings.png" width="620" alt="miel settings with grouped options, a game layout selector, and A and B controller actions">

<img src="assets/showcase/emulator-settings.png" width="620" alt="Per-game mGBA settings with Game Boy model, palette, borders and solar sensor options">

</details>

## Get started

1. Open **Settings** with **Select** or the gear.
2. Choose a **ROM folder**, pair **RomM**, or both.
3. Pick a game and press **A**. Allow file access when asked so miel can keep your saves.

To make miel your Home screen, choose **Set as default launcher** in Settings.

To supply your own game names, metadata or artwork, add [local sidecar files](docs/local-metadata.md)
beside your ROMs and rescan the folder.

Bring your own games: miel includes no ROMs, firmware, keys or console system data. It is designed
for the AYN Thor rather than as a general-purpose Android emulator frontend.

Emulator defaults live under **Emulators** in Settings. For a game-specific override, open
**X → More**, then **Y → Emulator settings**. Changes apply on the next launch; some options
need **Start fresh**.

PlayStation games can use CHD, PBP, ISO, BIN, CUE/BIN, M3U disc playlists or PS-X EXE homebrew.
Keep them in a `psx`, `ps1` or `PlayStation` folder so miel can identify the platform. ZIP bundles
from RomM keep their disc files together. For multi-disc games, tap **Change disc** on the lower screen
or click the right stick to select a disc. A BIOS is optional; placing your own `scph5500.bin`,
`scph5501.bin` or `scph5502.bin` in `/sdcard/miel/system/` improves compatibility.

PlayStation games use a DualShock controller by default. The lower-screen **Analog controls**
switch selects DualShock or the original digital controller, remembering your choice
for each game. **L1 + R1 + Select** toggles the DualShock’s Analog mode when a game needs it.
During play, touch the dimmed lower screen to brighten it; it dims again after eight seconds
of inactivity.

PSP games run on the upper screen with 2× rendering resolution by default and left-stick controls.
Keep ISO, CSO, PBP, CHD, ELF or PRX files in a `PSP` or `PlayStation Portable` folder;
ZIP archives containing a game also work. PPSSPP's runtime assets are bundled; no BIOS is needed.
In-game saves live under `/sdcard/miel/saves/PPSSPP/<game>/PSP/SAVEDATA/`.
Home and Back save a checkpoint for automatic resume. RomM syncs each game’s in-game saves
as a ZIP containing its `PSP/SAVEDATA` folder.

## PC games with DroidDeck

Install and set up [DroidDeck](https://github.com/Droid-Deck/DroidDeck), then install your games there.

1. Export DroidDeck's game files into a subfolder of miel's selected ROM folder, such as `PC/`.
2. Keep the exported `.steam` files and their accompanying `images/` folder together.
3. In miel, choose **Settings → Rescan folder**. Your games appear under **PC**; press **A** to
   launch one through DroidDeck on the upper screen.

Steam games get portrait covers, hero artwork, release years and genres directly from Steam,
without an API key. Downloaded artwork and metadata stay cached; unsuccessful requests are tried
once until **Settings → Redownload metadata** retries them. Non-Steam exports use DroidDeck's
exported icon. [Local sidecar files](docs/local-metadata.md) can override metadata and artwork.

DroidDeck manages PC game compatibility, settings and saves. miel's embedded checkpoints and
RomM save sync do not apply to these games.

## Controls

| Button | In the library |
| --- | --- |
| **D-pad / left stick** | Move between games or apps; hold left or right to scroll faster |
| **A** | Play, continue or open an app |
| **B** | Close a menu or leave Apps |
| **X** | Game options; in Apps, open on the other screen |
| **L / R** | Previous or next tab |
| **Select** | Settings |

The library has Recent, platform and Apps tabs. Platform tabs appear when they contain games.
Drag the tiles horizontally to scroll. Tap a game to select it, then tap it again or press
**Play** or **Continue** to launch it; tap an app to open it.

During a game, **Home** or Android's **Back** saves your place and returns to
the library.

## Azahar improvements

miel builds Azahar from pinned source with [patches](third_party/azahar/patches) for its embedded
libretro core:

- **Reusable shader caches.** Two file-handling fixes let shader caches save and load correctly,
  allowing compiled shaders to be reused between sessions.
- **Safer StreetPass handling.** Missing StreetPass files return an error instead of crashing the
  emulator.
- **The clock catches up.** Games using the device clock resynchronize after loading a checkpoint
  or resuming from sleep, rather than continuing with the time they were paused at.

The RTC work is adapted from [martyfuhry](https://github.com/martyfuhry)'s
[Azahar clock-resync fork](https://github.com/martyfuhry/azahar/releases/tag/clock-resync-v3), with
integration for miel's libretro core. See the [attribution](third_party/azahar/NOTICE).

## License

miel's own code is licensed under [GPL-3.0-or-later](LICENSE). Third-party cores, libraries,
fonts and showcase media retain their individual licenses. Binary releases include a
`miel-source.tar.gz` archive containing build scripts, patches, core source archives and fetched
core dependencies. License information is available in **Settings → Licenses** and under
`assets/notices/` inside the APK.
