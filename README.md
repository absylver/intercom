# Oron Intercom: Android app

Native Android app for camera operators. It works with the laptop's **OronIntercom.exe** (v1.3 or newer)
and keeps running with the screen locked, like a phone call.

- Finds the control laptop on the WiFi automatically (UDP beacon on port 41234)
- Runs as a foreground service with a "Connected" notification (Talk / Leave buttons)
- Earphone button push-to-talk: quick press toggles, hold talks while held
- Audio goes to wired or Bluetooth earphones, never the loudspeaker
- Short beep when the director starts speaking and when you start talking
- Reconnects on its own when the WiFi drops

## Build the APK

Needs JDK 17+ and the Android SDK (or just Android Studio).

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk  (rename to OronIntercom.apk)
```

Or push this folder to a GitHub repository: the included workflow builds the APK automatically
(Actions tab → latest run → OronIntercom-apk).

The APK is signed with a debug key, which is fine for installing directly on your team's phones.
For the Play Store you would create a release key.
