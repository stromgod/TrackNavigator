# TrackNavigator Repo Map

## Keep In Git (source and required project files)

- `.gitignore` - ignore rules for generated/local files.
- `settings.gradle` - Gradle module include configuration.
- `build.gradle` - root Gradle build script.
- `gradle.properties` - shared Gradle/JVM settings for the project.
- `gradlew`, `gradlew.bat` - Gradle Wrapper launch scripts.
- `gradle/wrapper/gradle-wrapper.properties` - pinned Gradle distribution configuration.
- `gradle/wrapper/gradle-wrapper.jar` - wrapper bootstrap binary.

### App module

- `app/build.gradle` - Android app module build configuration.
- `app/proguard-rules.pro` - custom ProGuard/R8 rules.
- `app/src/main/AndroidManifest.xml` - app manifest (activities, permissions, app metadata).
- `app/src/main/java/com/example/tracknavigator/MainActivity.java` - main menu screen.
- `app/src/main/java/com/example/tracknavigator/RecordTrackActivity.java` - record GPS track and save GPX.
- `app/src/main/java/com/example/tracknavigator/RaceActivity.java` - follow a GPX track with deviation hints.
- `app/src/main/java/com/example/tracknavigator/GpxHelper.java` - GPX read/write utilities.
- `app/src/main/java/com/example/tracknavigator/GeoUtils.java` - geodesic/math helpers.
- `app/src/main/java/com/example/tracknavigator/LatLngPoint.java` - coordinate model.
- `app/src/main/res/layout/activity_main.xml` - main screen layout.
- `app/src/main/res/layout/activity_record.xml` - recording screen layout.
- `app/src/main/res/layout/activity_race.xml` - race/follow screen layout.
- `app/src/main/res/values/strings.xml` - default localization strings.
- `app/src/main/res/values-ru/strings.xml` - Russian localization strings.
- `app/src/main/res/values/colors.xml` - color resources.
- `app/src/main/res/values/themes.xml` - app theme resources.
- `app/src/main/res/values/styles.xml` - widget/text styles.
- `app/src/main/res/drawable/ic_launcher1.xml` - currently used app icon drawable.
- `app/src/main/res/drawable/ic_launcher_background.xml` - adaptive icon background.

### Project docs/meta (optional but useful)

- `project_meta/README_APP_NOTES.txt` - internal technical notes.
- `project_meta/REPO_MAP.md` - this repository map.

## Usually Do NOT Keep In Git (generated/local machine files)

- `.gradle/` - local Gradle cache/state.
- `build/` - root-level generated build outputs/reports.
- `app/build/` - app module generated intermediates and outputs.
- `app/release/` - generated release artifacts metadata/output.
- `local.properties` - local Android SDK path (machine-specific).
- `.idea/workspace.xml` - IDE local workspace state.
- `.idea/deploymentTargetSelector.xml` - IDE local deployment target state.

## IDE Metadata (team choice)

Files in `.idea/` can be partially versioned depending on team policy:

- Often acceptable in git: `misc.xml`, `vcs.xml`, `gradle.xml`, `compiler.xml`, `runConfigurations.xml`.
- Better excluded: user/session-specific files (`workspace.xml`, deployment/temporary cache files).

## Notes

- `app/src/main/res/drawable-v26/ic_launcher.xml` and `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` exist, but current manifest points to `@drawable/ic_launcher1` as icon source.
- Generated report file `build/reports/problems/problems-report.html` is not source code and can be cleaned/re-generated.
