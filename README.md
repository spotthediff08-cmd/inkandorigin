# Animation Studio — Android starter project

Upload the CONTENTS of this ZIP to the root of a new GitHub repository. Do not upload the ZIP itself and do not nest the project inside another folder.

The repository root should contain:
- `.github/workflows/android.yml`
- `app/`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`

## Build
1. Open the repository's Actions tab.
2. Select Android Debug APK.
3. Choose Run workflow if it has not started automatically.
4. When the run is green, open it and download the AnimationStudio-debug artifact.

## Included
- Home, Scenes, Subtitles and Export screens.
- Multi-image picker and image thumbnails.
- Voiceover audio file picker.
- SRT file picker and editable subtitle text area.
- Global camera-motion selection UI.
- Output resolution selection UI.
- GitHub Actions build workflow.

## Important
This is a starter foundation, not a finished video editor. Actual MP4 rendering/encoding, SRT timestamp parsing and subtitle burn-in, 3x3 contact-sheet splitting, per-scene duration editing, transitions, preview, project save/load and final export are not implemented yet. These must be added and tested in later stages. No paid AI API is required.
