# APK Build Checklist (TrackNavigator)

## 1) Safe to delete before build

- [ ] `build/`
- [ ] `app/build/`
- [ ] `app/release/`
- [ ] `build/reports/` (including `problems-report.html`)
- [ ] `.idea/workspace.xml`
- [ ] `.idea/deploymentTargetSelector.xml`

These are generated/local files and do not contain app source code.

## 2) Must exist (do not delete)

- [ ] `settings.gradle`
- [ ] `build.gradle` (root)
- [ ] `app/build.gradle`
- [ ] `app/src/main/AndroidManifest.xml`
- [ ] `app/src/main/java/**` (all app Java classes)
- [ ] `app/src/main/res/**` (layouts, strings, themes, drawables, etc.)

If any of these are missing, APK build will fail.

## 3) Needed for Gradle Wrapper build (`gradlew`)

- [ ] `gradlew`
- [ ] `gradlew.bat`
- [ ] `gradle/wrapper/gradle-wrapper.properties`
- [ ] `gradle/wrapper/gradle-wrapper.jar`

Without these, `./gradlew assembleDebug` (or `gradlew.bat`) cannot run in a standard way.

## 4) Environment / machine-specific

- [ ] `local.properties` is present OR Android SDK path is provided via environment.
- [ ] Java/JDK is installed and compatible with project settings.

`local.properties` is local and usually not committed to git.

## 5) Optional (does not affect APK build result)

- [ ] `project_meta/**` (notes/docs/checklists)
- [ ] Most `.idea/**` metadata files
- [ ] Markdown/txt docs

## 6) Quick pre-build verification

- [ ] Open `app/build.gradle` and verify `compileSdk`, `minSdk`, `targetSdk`.
- [ ] Ensure package/activity names in `AndroidManifest.xml` match source classes.
- [ ] Ensure no referenced resource is missing (`@string/...`, `@layout/...`, `@drawable/...`).

## 7) Build commands

- [ ] Debug APK: `gradlew.bat assembleDebug`
- [ ] Release APK: `gradlew.bat assembleRelease`

Artifacts are usually generated in `app/build/outputs/apk/`.
