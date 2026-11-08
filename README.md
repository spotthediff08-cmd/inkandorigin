# Animation Studio

An offline-first Android image-to-video studio. Projects and imported assets are stored locally on the device. No paid AI service or cloud account is required for editing or export.

## Current features

- Import multiple images or recursively import images from a selected folder.
- Import voiceover audio and SRT subtitle files.
- Edit SRT text, inspect parsed cue timestamps, and configure subtitle size, color, position, outline, and background.
- Preview the scene timeline with camera motion, synchronized voiceover playback, and timed SRT subtitle cues.
- Build storyboards from 2×2, 3×3, or 4×4 contact sheets; review possible blank panels, select panels manually, and split them into scenes.
- Reorder scenes, change per-scene duration, apply camera motion presets, and use undo/redo for scene edits.
- Automatically save projects locally; reopen, duplicate, rename by editing the project name, or delete projects.
- Export MP4 using H.264 video and AAC audio, with SRT subtitles burned into the video.
- Choose 16:9, 9:16, or 1:1; 720p, 1080p, or 4K; 24, 30, or 60 fps; and None or Fade through black scene transitions.
- Save completed exports to the device's Movies/Animation Studio folder using the project name, and keep a local export history. Export progress is shown while rendering, with a cancel action that removes the temporary output.

## Export notes

- Word highlighting is distributed evenly across each SRT cue. It is approximate unless the SRT contains word-level timing.
- The in-app timeline preview plays the selected voiceover and synchronizes the visual timeline to audio playback. If audio cannot be prepared by the device, visual preview remains available.
- Fade through black is available as a scene transition. True overlapping crossfades are not currently supported by the Media3 Composition API.
- 4K and 60 fps availability and performance depend on the phone's hardware and encoder support. If export fails, try 1080p at 30 fps.

## Build a debug APK

1. Open the repository's **Actions** tab.
2. Open the latest **Android Debug APK** workflow run.
3. When the run succeeds, download the **AnimationStudio-debug** artifact.
4. Extract the ZIP to find `app-debug.apk`.

The workflow builds the APK automatically after commits to `main`, or can be started manually with **Run workflow**.
