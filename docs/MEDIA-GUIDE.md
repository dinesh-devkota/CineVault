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

Cine-Media does not transcode. Video playback on Apple TV supports MP4, M4V, and
MOV; Android supports additional containers, including MKV. Audio/video codec
support still depends on the device. A file appearing in the library does not
guarantee it can play on every platform.
