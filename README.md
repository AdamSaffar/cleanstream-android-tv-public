# CleanStream

> **Technical Beta for Android TV and Google TV**

CleanStream is a content-filtering platform built around a straightforward idea: viewers should be able to retain control over the media they watch without giving up the original streaming experience. The project combines a large pre-processed filter library with an Android TV application that applies those filters during playback.

The current beta contains **more than 700 prepared Netflix titles**. For each supported title, CleanStream can provide replacement captions, visual word redaction, precise mute windows, and scene skips. The title continues to play through Netflix; CleanStream operates alongside it, coordinating the filtering experience in real time.

Although this beta library was prepared around Netflix for practical reasons, the broader CleanStream concept is not limited to Netflix. The filtering pipeline produces timestamped filter data, while the runtime model is built around ordinary playback controls: observing position, muting and restoring audio, and moving past selected ranges. With a validated integration layer for another streaming application, the same model could be extended to other services on supported Android-based television platforms.

https://github.com/user-attachments/assets/e3351436-4fb5-418a-bd67-4c0532fb5025

## The vision

CleanStream is intended for viewers and families who want a more deliberate relationship with the media in their homes. It is meant to serve people who may enjoy a film or series while still preferring to avoid certain language, sexual material, or other selected content categories.

The long-term vision is a polished, accessible television application: a viewer opens a familiar catalog, chooses a title, and receives a carefully filtered version of that title without needing to think about the underlying timing system. The present build is a technical beta, but the broader purpose is to explore whether a local, data-driven filtering system can make that experience more precise and more adaptable than broad, one-size-fits-all filtering.

## What CleanStream does

CleanStream combines several forms of filtering that work from the same timed filter file:

- **Replacement captions** that appear over supported playback.
- **Visual redaction** for configured words within the replacement captions.
- **Word-level mute windows** that aim to mute only the relevant portion of dialogue instead of routinely silencing an entire subtitle cue.
- **Scene skips** for configured portions of a title.
- **Dynamic synchronization recovery** after seeking, buffering, and supported ad transitions.
- **An Android TV catalog** with search, genre rows, and poster artwork for the prepared title library.

The goal is not simply to remove unwanted content, but to filter it precisely enough that the surrounding dialogue, story, and overall viewing experience remain as uninterrupted as possible.

## How the Android TV app works

Each supported title has a local JSON filter file named after its Netflix title ID. The file contains timed caption cues, mute windows, skip ranges, and related metadata. The CleanStream catalog only lists titles with completed filter files, so selecting a title means the playback engine has actual filter data to load.

When a viewer chooses a title, CleanStream loads the local filter file and opens the corresponding Netflix title. Netflix remains responsible for video delivery and playback. CleanStream observes the playback position, renders its replacement captions, applies redaction, schedules mute windows, and requests scene skips at the appropriate moments.

The most difficult part of this process is preserving synchronization when playback does not behave like a simple uninterrupted clock. A viewer can rewind, fast-forward, pause, or encounter a pre-roll or mid-roll advertisement. CleanStream therefore treats a skip as incomplete until it can confirm that playback has reached the intended location. During uncertain transitions, it holds its captions rather than displaying text from the wrong point in the title. After an ad transition, it re-establishes the target position from observed playback progress instead of relying on a universal fixed delay.

## CleanStream in action

The demonstrations below show CleanStream operating during normal Netflix playback. Each example compares the original playback with the same moment using CleanStream's filtering system.

### 1. Word-level profanity filtering

**The Big Lebowski** demonstrates CleanStream's word-level profanity handling. Flagged words are muted and visually redacted while the surrounding dialogue remains audible.

**Original playback**

https://github.com/user-attachments/assets/9226ca80-6ed0-4987-9124-28e72cf794f0

**CleanStream enabled**

https://github.com/user-attachments/assets/63b82267-ad20-4722-938d-dafefdbfabb6

---

### 2. Scene skipping

**The Vow** demonstrates CleanStream's scene-skipping system. The configured scene range is bypassed automatically, with playback resuming at the intended point in the title.

**Original playback**

https://github.com/user-attachments/assets/944bd337-b287-456f-9472-d29f77fe2076

**CleanStream enabled**

https://github.com/user-attachments/assets/c4c374a0-6edb-4869-8f4c-56479ef3b152

## Supported platforms

The current release supports **Android TV** and **Google TV**, which provide the Android framework and system features required by CleanStream’s playback and filtering components.

| Platform | Status | Explanation |
| --- | --- | --- |
| Android TV | Supported technical beta | Primary platform for the current app and test workflow. |
| Google TV | Supported technical beta | Uses the same Android TV foundations as the current application. |
| Fire OS | Evaluated; not presently supported | Fire OS is Android-derived, but a separate compatibility and validation effort would be necessary before offering it as a supported target. |
| Tizen OS | Evaluated; unsupported | A separate Tizen application and synchronization implementation would be required. |
| tvOS | Evaluated; unsupported | Apple TV would require an independent tvOS application and a different system-integration strategy. |

CleanStream’s filtering model could potentially be adapted to other platforms, but the current application is built specifically for Android. Supporting a different platform would require a separate integration and compatibility effort rather than simply rebuilding the existing app for another device.

## Limitations

CleanStream is designed to improve the viewing experience, but its filtering is not perfect. It may occasionally miss content, flag material unnecessarily, or apply a filter at an imperfect time when the source material is unclear. Context can also be difficult to interpret, since the same word or scene may be acceptable in one situation but inappropriate in another.

CleanStream also relies on external systems that can change over time. Device firmware, streaming-app updates, buffering behavior, advertisements, and media-session changes may introduce new synchronization issues. The current beta has been tested across common playback, seeking, skipping, and ad-recovery scenarios, but compatibility cannot be guaranteed in every situation.

Finally, the catalog is limited by the prepared filter library. A title can be filtered only when a corresponding filter file exists and has been installed on the device.

## Installation

This technical beta is distributed as a signed Android APK together with a separate filter-pack archive. The application and filter pack are installed once and remain on the device after the app is closed or the television is restarted.

### Requirements

- An Android TV or Google TV device with ADB enabled.
- Android Platform Tools on a computer that can connect to the device.
- The signed CleanStream APK from the release.
- The matching filter pack, extracted into a folder containing filter files.
- A valid Netflix account and the Netflix application installed on the television.

### First-time setup

Replace the placeholders below with your own device address and file paths.

| Placeholder | Meaning |
| --- | --- |
| `<device>` | The ADB device address, such as `192.168.1.50:5555`. |
| `<apk>` | The path to the signed CleanStream APK. |
| `<filters>` | The extracted folder that contains the `filter_*.json` files. |

1. Connect to the television and install CleanStream.

   ```powershell
   adb connect <device>
   adb -s <device> install -r <apk>
   ```

2. Copy the filter files to CleanStream's device folder.

   ```powershell
   adb -s <device> push <filters> /sdcard/Android/data/com.cleanstream.engine/files/
   ```

3. Grant the permissions used by the current beta.

   ```powershell
   adb -s <device> shell appops set com.cleanstream.engine SYSTEM_ALERT_WINDOW allow
   adb -s <device> shell cmd notification allow_listener com.cleanstream.engine/.MediaWatcher
   adb -s <device> shell pm grant com.cleanstream.engine android.permission.READ_LOGS
   ```

4. Open CleanStream from the television's application grid. Confirm the overlay and notification-listener permissions if the device asks.

Repeat this setup only after uninstalling CleanStream, clearing its app data, factory-resetting the device, or replacing the filter pack with a newer release.

## Permissions and local operation

CleanStream uses an overlay to draw its replacement captions and a notification-listener component to observe the active Android media session. The current technical beta also uses `READ_LOGS`, granted through ADB, as part of its ad-boundary diagnostics. These requirements reflect the present Android implementation and would need to be reconsidered for a consumer-oriented release.

The app works from locally installed filter files. It does not provide a Netflix subscription, redistribute Netflix video, or alter the underlying stream. Users remain responsible for their own streaming access and for ensuring that any filter packs, subtitle-derived materials, screenshots, or demonstration clips they distribute are handled lawfully.
