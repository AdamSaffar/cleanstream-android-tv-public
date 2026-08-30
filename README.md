# CleanStream

> **Technical Beta for Android TV and Google TV**

CleanStream is a content-filtering platform built around a straightforward idea: viewers should be able to retain control over the media they watch without giving up the original streaming experience. The project combines a large pre-processed filter library with an Android TV application that applies those filters during playback.

The current beta contains **more than 700 prepared Netflix titles**. For each supported title, CleanStream can provide replacement captions, visual word redaction, precise mute windows, and scene skips. The title continues to play through Netflix; CleanStream operates alongside it, coordinating the filtering experience in real time.

Although this beta library was prepared around Netflix for practical reasons, the broader CleanStream concept is not limited to Netflix. The filtering pipeline produces timestamped filter data, while the runtime model is built around ordinary playback controls: observing position, muting and restoring audio, and moving past selected ranges. With a validated integration layer for another streaming application, the same model could be extended to other services on supported Android-based television platforms.

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

The objective is not merely to remove content. It is to do so with enough temporal precision that dialogue, story structure, and the viewer's sense of continuity remain as intact as possible.

## The filtering pipeline

The Android TV application is the playback layer of CleanStream, but the most substantial work occurs earlier in the filtering pipeline. That pipeline is responsible for transforming source subtitle and timing material into the per-title JSON files that drive every caption replacement, mute, and skip decision.

Its current tools include:

- `ttml_profanity.py`
- `qwen3_engine.py`
- `cleanstream_build_mutes.py`
- `cleanstream_build_skips.py`
- `cleanstream_build_skips_batched.py`
- `cleanstream_orchestrator`

Together, these tools form a multi-stage workflow: they ingest subtitle and timing material, identify candidate moments, establish word-level or scene-level timing, generate mute and skip schedules, and write a finished filter file for review and deployment. That workflow is what makes a library of hundreds of prepared titles possible.

### Why the pipeline matters

Commercial filtering services often depend on substantial manual editorial work. CleanStream investigates a different direction: automating as much of the preparation process as possible while keeping the resulting data concrete, inspectable, and correctable on a title-by-title basis.

The practical advantage is scale. Rather than requiring every title to be fully assembled by hand, the pipeline can prepare a large initial library and leave room for targeted review where errors, omissions, or false positives are discovered. The technical advantage is precision: a filter file can preserve the exact times at which a word should be redacted, a mute should begin, or a scene should be skipped.

### How the pipeline should be presented

The filtering pipeline deserves its own repository. It has a distinct purpose, a separate execution environment, different dependencies, and a different audience from the Android TV app. Keeping it separate will make both projects easier to understand:

| Repository | Purpose |
| --- | --- |
| **CleanStream Android TV** | The television application, playback synchronization, catalog interface, and local filter-file runtime. |
| **CleanStream Filter Pipeline** | The multi-stage processing tools that create and validate `filter_*.json` files. |

This is a common open-source arrangement: one repository contains the product that users run, while another contains the generation, data-processing, or research pipeline that produces the product's inputs. GitHub does not need a special feature to connect them; each repository can link clearly to the other in its README, release notes, and documentation.

When the pipeline repository is ready, add a direct link here along with a diagram of its stages, its dependency requirements, an explanation of its inputs and outputs, and a small non-copyrighted sample filter. That repository should be where the project explains its most original technical work in depth.

## How the Android TV app works

Each supported title has a local JSON filter file named after its Netflix title ID. The file contains timed caption cues, mute windows, skip ranges, and related metadata. The CleanStream catalog only lists titles with completed filter files, so selecting a title means the playback engine has actual filter data to load.

When a viewer chooses a title, CleanStream loads the local filter file and opens the corresponding Netflix title. Netflix remains responsible for video delivery and playback. CleanStream observes the playback position, renders its replacement captions, applies redaction, schedules mute windows, and requests scene skips at the appropriate moments.

The most difficult part of this process is preserving synchronization when playback does not behave like a simple uninterrupted clock. A viewer can rewind, fast-forward, pause, or encounter a pre-roll or mid-roll advertisement. CleanStream therefore treats a skip as incomplete until it can confirm that playback has reached the intended location. During uncertain transitions, it holds its captions rather than displaying text from the wrong point in the title. After an ad transition, it re-establishes the target position from observed playback progress instead of relying on a universal fixed delay.

## CleanStream in action

The strongest public demonstration of CleanStream will be a small collection of clear before-and-after examples. Each example should be short, accurately labeled, and selected carefully so that it demonstrates the feature without becoming graphic or difficult to publish.

| Demonstration | Before filter | After filter | What it demonstrates |
| --- | --- | --- | --- |
| **Profanity-heavy dialogue** | Add link when available | Add link when available | Caption redaction and the narrow timing of a mute window. |
| **Visual-content skip** | Add link when available | Add link when available | A non-graphic scene-skip range and the return to properly synchronized playback. |
| **Mixed filtering** | Add link when available | Add link when available | Captions, a mute window, and a skip working together in one sequence. |

Use only footage, screenshots, or recreations that you are authorized to distribute. If licensing a real film or television clip is impractical, a recreated demonstration with an on-screen timing visualization can still communicate the technology effectively.

## Supported platforms

The present release targets **Android TV** and **Google TV**. These environments provide the Android application model and system capabilities on which the current playback layer depends.

| Platform | Status | Explanation |
| --- | --- | --- |
| Android TV | Supported technical beta | Primary platform for the current app and test workflow. |
| Google TV | Supported technical beta | Uses the same Android TV foundations as the current application. |
| Fire OS | Evaluated; not presently supported | Fire OS is Android-derived, but a separate compatibility and validation effort would be necessary before offering it as a supported target. |
| Tizen OS | Evaluated; unsupported | A separate Tizen application and synchronization implementation would be required. |
| tvOS | Evaluated; unsupported | Apple TV would require an independent tvOS application and a different system-integration strategy. |

The distinction is important: CleanStream's filtering concept may be broadly applicable, but the current application is an Android implementation. Extending the product to another platform would require more than rebuilding the same code for a new device.

## Limitations

CleanStream is designed to be useful, not infallible. The filtering process can miss content, flag content that a viewer would not consider objectionable, or produce imperfect timing when the available source material is ambiguous. Context is especially difficult: a word can be harmless in one setting and inappropriate in another, while an ostensibly obvious word can be part of dialogue a viewer would prefer to retain.

The project also depends on changing external conditions. Device firmware, streaming-app updates, buffering behavior, advertisements, and media-session behavior can create new synchronization edge cases. The current beta has been tested through representative playback, seeking, skip, and ad-recovery scenarios, but it should not be treated as a universal compatibility guarantee.

Finally, the catalog is limited by the prepared filter library. A title can be filtered only when a corresponding filter file exists and has been installed on the device.

## Installation

This technical beta is distributed as a signed Android APK together with a separate filter-pack archive. The application and filter pack are installed once and remain on the device after the app is closed or the television is restarted.

### Requirements

- An Android TV or Google TV device with ADB enabled.
- Android Platform Tools on a computer that can connect to the device.
- The signed CleanStream APK from the release.
- The matching filter pack, extracted into a folder containing `filter_*.json` files.
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

### Updating

Install a newer signed APK over the existing release installation, then copy newer filter files to the same folder when a new filter pack is published. Preserve a backup of the original filter pack before manually modifying any JSON files.

> **Note:** Debug APKs and signed release APKs have different signing certificates. Android cannot ordinarily install one over the other as an update. Moving from a debug build to the signed release may require uninstalling the debug app first, which also removes local app data and filters.

## Permissions and local operation

CleanStream uses an overlay to draw its replacement captions and a notification-listener component to observe the active Android media session. The current technical beta also uses `READ_LOGS`, granted through ADB, as part of its ad-boundary diagnostics. These requirements reflect the present Android implementation and would need to be reconsidered for a consumer-oriented release.

The app works from locally installed filter files. It does not provide a Netflix subscription, redistribute Netflix video, or alter the underlying stream. Users remain responsible for their own streaming access and for ensuring that any filter packs, subtitle-derived materials, screenshots, or demonstration clips they distribute are handled lawfully.

## Project notes

CleanStream is independent and is not affiliated with, endorsed by, or sponsored by Netflix, VidAngel, ClearPlay, Google, Amazon, Samsung, or Apple. Netflix and the names of other services are trademarks of their respective owners.

No software license has been selected for this repository yet. Until a license is added, readers should not assume that copying, redistributing, or modifying the source code is permitted.
