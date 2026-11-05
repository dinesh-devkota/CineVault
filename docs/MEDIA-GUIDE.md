# Cine-Media user guide

Cine-Media organizes media stored on your NAS and plays it directly over your
network. Your files stay on the NAS; no media server or transcoding box is needed.

## Connect one shared parent folder

In **Sources**, add your NAS and select the share containing your media. Enter
credentials only in the app. A read-only NAS account is recommended; keep its
password out of chat, screenshots, and issue reports.

If the share itself is named `Media`, select it and leave **Folder inside share**
empty. If `Media` is inside another share, select that share and choose its `Media`
folder. Cine-Media scans the selected folder and all its subfolders recursively.
You do not need to add each movie, show, or category separately.

A source pointing to `Media/Movies` finds only that folder and its descendants.
It cannot find sibling `Music` or `TV Shows` folders. Edit the source to use the
common `Media` parent, then scan again.

## Choose what to scan or ignore

Open **Sources → Edit** for the NAS. Use **Choose scan folder** to browse the
share, open subfolders, and select **Use this folder** or the share root.

Under **Ignored folders and files**, open the folder/file picker. Choose
**Ignore folder** to exclude that folder and all its descendants, or **Ignore
file** to exclude only that file. You can also enter a relative path manually.
For example, if the selected scan folder is `Media`, use `Projects` or
`Movies/Extras/trailer.mp4`, without the NAS address or `Media` prefix.

Choose **Save & Scan** to apply the rules. Already indexed excluded items
disappear from this source's library immediately; the NAS files and their saved
playback history are kept. To restore an item, choose **Include again** (or
**Stop ignoring** on Apple TV), save, and let the scan finish. Rules apply only
to that NAS source. If another source scans the same files, set its rules too.

Changing the NAS address, port, share, or scan folder clears its relative
exclusions so they do not apply to unrelated folders. Select the exclusions
again for the new location.

Software dependency folders such as `node_modules` and TypeScript declaration
files (`.d.ts`) are skipped automatically. Other `.ts` files are checked for
MPEG transport-stream content before being added, because `.ts` can also mean
TypeScript source code. Rescan an existing source to clear previously imported
code files from the library.

## Use clear names

For example:

```text
Media/
  Movies/Arrival (2016)/Arrival (2016).mkv
  Documentaries/Documentary Title (2020)/Documentary Title (2020).mp4
  TV Shows/Show Name (2020)/Season 01/ShowName.S01E01.mkv
  Music/Artist/Album/01-Song.mp3
```

Include a title and release year for movies and documentaries. For television,
include a season/episode marker such as `S01E01`. Keep each show's seasons under
one show folder. Browse a show's master poster, choose a season, then select an
episode tile to play it.

Names such as `[1x02]`, `S01 E02`, and `S01xE02` also work, including folders
named `Show Name Season 1`. Under `TV Shows`, the show folder supplies the shared
title even when episode filenames use a different title for each season. Local
grouping does not require an artwork key or another scan after an app update.
Unnumbered extras need a season/episode marker, such as `S00E01`, to appear in
the show's **Specials** season.

For audio music, consistent artist and album folders help identify the files.
TMDB is not an audio album catalog: its Music category describes screen content
such as music films, not MP3 artist, album, or cover-art information.

## Add metadata and artwork

For personal use, obtain a free TMDB API key or read access token from
themoviedb.org under **Settings → API**. Enter it in Cine-Media's **Settings** and
choose **Save Key & Fetch Missing Metadata**. Do not paste the key into chat.
Matching sends the parsed title and year to TMDB; your media files stay on the NAS.
See [TMDB's API FAQ](https://developer.themoviedb.org/docs/faq) for personal-use terms.

Matching provides real titles, descriptions, posters, and categories. A matched
movie's metadata takes priority over folder-name guesses when classifying movies,
documentaries, and music-related films.

Without a key, new unmatched items use filename/folder information and placeholder
artwork. A folder label alone cannot identify the correct movie. If an item is
unknown or incorrectly matched, check its title and year and use the manual match
correction. Different films or series can share the same name.

## Check the scan and playback

Keep the NAS reachable while scanning. Progress and partial results can appear
before completion; they do not mean every folder has finished. Check the status
and the last successful scan time. If a scan fails, correct the connection or
credentials and retry.

Cine-Media does not transcode. A filename extension identifies a candidate
format; the file must contain real media, and the device must support the
audio/video codecs inside it. Unsupported formats show an explanation instead
of Play controls. Apple TV also checks the actual asset before starting playback.

| Platform | Video candidates | Audio candidates |
| --- | --- | --- |
| Android TV | MP4, M4V, MOV, MKV, WebM, AVI, MPEG-TS, MPEG-PS, FLV | MP3, M4A, AAC, FLAC, WAV, Ogg, Opus |
| Apple TV | MP4, M4V, MOV | MP3, M4A, AAC, FLAC, WAV, AIFF/AIF |

This table describes the current app's file playback, not every format supported
by other players. Android's AVI support depends on its extractor and device
codecs; FLV seeking is limited. WMV, ISO, and M2TS are cataloged but cannot be
played by these built-in players. WMA cannot play on either platform, and AIFF/AIF
cannot play in this Android build. Unsupported real media can remain in the
library for organization.

The `.ts` suffix is shared by MPEG transport-stream video and TypeScript source
code. `.d.ts` is a TypeScript declaration file and is never media. Scanning checks
the packet structure of ambiguous `.ts` files; Android repeats that check before
playing an existing entry. Android's TS player requires standard 188-byte packets;
192-byte M2TS and 204-byte variants are not enabled for playback. The player then
checks whether it can decode its tracks.
Standalone TS files are not enabled for playback in the current Apple TV app.

Format references: [Android Media3 progressive formats](https://developer.android.com/media/media3/exoplayer/progressive),
[Media3 codec support](https://developer.android.com/media/media3/exoplayer/supported-formats),
[the app's Media3 1.8.0 extractors](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/DefaultExtractorsFactory.java),
[its TS packet parser](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java),
[Apple asset playability](https://developer.apple.com/documentation/avfoundation/avasset/isplayable),
and [TypeScript declaration files](https://www.typescriptlang.org/docs/handbook/2/type-declarations.html).
