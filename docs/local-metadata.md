# Local metadata and artwork

Place optional sidecar files in your chosen ROM folder, beside the game. Match the ROM's exact
filename, including capitalization, with its final extension removed:

```text
3ds/
  Adventure.zip
  Adventure.json
  Adventure.cover.png
  Adventure.screenshot.png
```

For an uncompressed ROM such as `Adventure.3dsx`, use the same sidecar names. Sidecars belong
outside ZIP archives. Each file is optional; you can supply just artwork or just metadata.

## Metadata

Save the JSON file as UTF-8:

```json
{
  "title": "Adventure",
  "year": 2026,
  "genre": "RPG",
  "region": "World"
}
```

| Field | Value |
| --- | --- |
| `title` | Display name |
| `year` | Release year, from 1 to 9999 |
| `genre` | Genre text |
| `region` | Region text |

Supplied fields override filename detection and RomM metadata. Omitted or blank fields keep their
detected or RomM values. Invalid years are ignored. Malformed or unreadable JSON is ignored, and
the file must be at most 64 KiB.

Overrides leave the game's identity, RomM link and saved progress intact.

## Artwork

- `.cover.png` supplies the library tile artwork.
- `.screenshot.png` supplies the gameplay artwork shown on the lower display while browsing.

Use valid PNG images, at most 4 MiB each, with neither dimension exceeding 2048 pixels. Local
images take precedence over downloaded artwork. The library crops covers to fit its tiles;
stacked DS and 3DS screenshots are cropped to their upper screen.

## Apply changes

Rescan the ROM folder after adding or editing sidecars. The same files work in folders chosen
through Android's folder picker. Clearing downloaded artwork keeps the sidecar files.

To remove an override, delete its sidecar file or omit the relevant JSON field, then rescan.
For a RomM-linked game, rescan while RomM is reachable to restore its metadata; an offline rescan
can retain the previously cached metadata.
