import SwiftUI

struct MediaGuideView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section {
                Button("Back to Settings") { dismiss() }
            }
            Section("One source for your whole library") {
                Text("Select your Media share and leave the folder empty. If Media is inside another share, choose its Media folder. Scans include every subfolder. A source ending in Media/Movies cannot find sibling Music or TV Shows folders; select their common parent instead.")
                    .focusable()
            }
            Section("Choose folders and files to ignore") {
                Text("Open Sources → Edit. Browse scan folders, then browse folders and files to ignore. Ignore a whole folder and its subfolders, or one file. Save & Scan applies the exclusions without deleting NAS files or watch history. Stop ignoring and rescan to restore them. TypeScript code and node_modules are skipped; genuine .ts video files are checked before import.")
                    .focusable()
            }
            Section("Names that help matching") {
                Text("""
                Media/Movies/Arrival (2016)/Arrival (2016).mkv
                Media/Documentaries/Title (2020)/Title (2020).mp4
                Media/TV Shows/Show Name (2020)/Season 01/ShowName.S01E01.mkv
                Media/Music/Artist/Album/01-Song.mp3
                """)
                .font(.system(.caption, design: .monospaced))
                .focusable()
            }
            Section("Posters, details, and categories") {
                Text("Enter your free personal-use TMDB API key or read access token in Settings, then save and fetch missing metadata. Without a key, new items use filename/folder information and placeholder artwork. Matched metadata takes priority over folder guesses. Correct ambiguous matches using the title and year. Keep your key in the app, not in chat.")
                    .focusable()
            }
            Section("TV shows and music") {
                Text("Use S01E01-style episode names. Open a show's poster, choose a season, then an episode. Keep audio music in artist/album folders. TMDB's Music category is for screen content; it does not provide an MP3 album catalog.")
                    .focusable()
            }
            Section("Supported playback formats") {
                Text("Video: MP4, M4V, and MOV. Audio: MP3, M4A, AAC, FLAC, WAV, and AIFF/AIF. Apple TV checks whether it can play the file before starting. Other media containers can be cataloged but do not have Play controls in this app. TypeScript .ts and .d.ts files are not media.")
                    .focusable()
            }
            Section("Scan and play") {
                Text("Enter NAS credentials only in the app; a read-only account is recommended. Partial results can appear before scanning finishes, so check the status and last successful scan time. For video, Apple TV supports MP4, M4V, and MOV with compatible codecs; Android supports more containers. Cine-Media does not transcode.")
                    .focusable()
            }
        }
        .navigationTitle("Cine-Media Guide")
    }
}
