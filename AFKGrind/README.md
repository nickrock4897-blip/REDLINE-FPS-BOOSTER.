# AFK Grind (Android)

AFK Grind is a local Android companion app for user-started Roblox sessions. It tracks session time, stores completed sessions on-device, checks accessible Android settings, and can show an optional movable overlay.

## Features
- Jetpack Compose dashboard with ON/OFF, pause/resume/end, timer and total session time.
- Local history and settings stored in SharedPreferences. No account, analytics or internet access.
- Optional overlay and notification-backed foreground service.
- Uses FLAG_KEEP_SCREEN_ON while the main activity is visible and while a permitted overlay is shown for a running session.
- Checks battery level, power-save mode, screen timeout, overlay permission and battery-optimization status.

## Android limits
- The floating menu needs explicit “Display over other apps” permission.
- Manual screen lock, force-stop, battery exhaustion, thermal shutdown, OEM restrictions or Roblox disconnects can interrupt a session.
- A regular app cannot reliably read another app's FPS or change Roblox's graphics engine, so no direct FPS boost is promised.
- Protected settings are not silently changed. The optimize action opens Android settings.
- The service declares Android 14+ special-use foreground-service purpose. OEM-specific policies may still differ.

## Build
Open this folder in Android Studio with JDK 17 and Android SDK 35. The GitHub Actions workflow builds it with Gradle 8.9 and uploads the generated debug APK as the AFK-Grind-debug-APK artifact.
