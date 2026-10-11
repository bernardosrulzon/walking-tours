# Project instructions

## Builds and hand-off

- To hand a build to a person, install it on a test device, or measure performance, build the
  **release** variant: `./gradlew :app:assembleRelease`. It is signed with the debug keystore on
  purpose (see the comment in `app/build.gradle.kts`), so `adb install -r` upgrades over a debug
  build in place — same signature, no uninstall, user progress kept — and the Google API keys,
  which are restricted to the debug signing fingerprint, keep working.
- The debug signing is a "for now" arrangement. Before anything ships to a store, the release
  build needs a real upload key and the Google keys need re-restricting to it.
- Do not judge performance from a debug build: same code, but JIT and class loading made a
  measured difference — 5.4% janky frames in release vs 20–23% in debug on a Galaxy S23.
- The Gradle wrapper needs the Android Studio JDK: `export JAVA_HOME="/Applications/Android
  Studio.app/Contents/jbr/Contents/Home"` (see README).
