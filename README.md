# YT Transcript Saver

A small Android app that turns a YouTube video link into a plain-text transcript.
It requests the video's publicly available caption track and saves the transcript
to a `.txt` file in Downloads. Videos must have captions enabled.

## Features

- Accepts regular YouTube links, Shorts, live links, embeds, and `youtu.be` links.
- Prefers human-authored captions, then English captions, then the first available track.
- Retries auto-generated captions through YouTube's Android player if its web caption feed is empty.
- Displays a scrollable preview, caption language, and word count before saving.
- Writes a UTF-8 text file with the video title, source link, language, and transcript.
- Saves directly to Downloads on Android 10 and newer; older devices use Android's file picker.
- Sends requests directly from the device to YouTube; there is no account or app server.

## Install with Obtainium

1. Add `https://github.com/classicfoo/yt-transcripts` as an app source in Obtainium.
2. Install the latest release APK offered by the repository.
3. Paste a public YouTube video link, tap **Get transcript**, then save to Downloads.

Obtainium can check the repository's GitHub Releases for future APK updates.

## Limitations

YouTube may not expose captions for private, age-restricted, or caption-disabled videos.
Some caption feeds require a session token or may be blocked on a network; YouTube can
also change its player and caption endpoints, so a future app update may be needed. The
app fetches caption text only and does not download video or audio.

## Build

The project uses the Android Gradle Plugin and the Gradle wrapper:

```sh
./gradlew :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
