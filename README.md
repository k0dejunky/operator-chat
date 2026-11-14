# Operator Chat (Android)

Thin Android client for the gallery site's operator chat. It connects to the
site's chat bridge webhooks with a Bearer token (a **per-device operator
token** — preferred — or the legacy shared `GALLERY_CHAT_KEY` during
migration) and lets a human operator reply to member chat messages. **No AI
logic and no API keys live on the device** — the server does all AI work.

## Contract
See `docs/chat-android-contract.md` in the `gallery-site` repo for the full
webhook set (inbox, thread/history, stream/events, reply, mode/read toggles,
attachments, app-update endpoints, training-PC endpoints).

## Build
The GitHub Actions workflow (`.github/workflows/build.yml`) builds a release
APK on every tag push. A signed release is built locally and published to
`public/assets/apk/OperatorChat-v{version}.apk` (see the gallery-site
AGENTS.md versioning rule — bump `versionName`/`versionCode` before every
release build):

```bash
./gradlew assembleRelease          # then sign with operatorchat-release.jks
```

Requires JDK 17 + Android SDK 35 (the CI provides both).

## v4 architecture

- **One activity** (`MainActivity`) and one Compose tree: four tabs
  (Chats, Live, Shows, Settings) and a conversation route. No activity per
  screen, no duplicate session.
- `AppViewModel` is the session spine (stored connection, lock, update
  offer). `InboxViewModel`, `ThreadViewModel`, `LiveViewModel`,
  `ScheduleViewModel` and `SettingsViewModel` own their screens; the transport
  (`ChatBridge`, `LiveToolsBridge`, `LiveApi`) and the crypto/verification
  (`SecurePrefs`, `Passcode`, `Updater`) are unchanged contracts.
- The design is the "studio" system: ink/ivory, amber actions, amethyst
  secondary (see `ui/theme/StudioTheme.kt` and `ui/components`).
- The broadcast still runs in `live/LiveBroadcastService` (foreground,
  camera/microphone/dataSync types) so it survives the screen; the Live tab
  only opens the camera when the operator presses Go live.
- The web/UI tests are Robolectric + Compose (`ScreenshotTest` renders every
  screen to `app/build/screenshots/`).

## Use
1. Open the app and enter the server URL: `https://amethyst2213.com/gallery`.
2. Enter a **per-device operator token** (admin panel → Operator app). A
   device token is required to start a broadcast; the legacy shared key can
   still read/reply to chat during migration.
3. Chats loads the unread queue; All/Favourites switch the list. Open a
   conversation to reply, attach media, pin or delete messages.
4. Live opens the camera only when you press Go live; the chat and the
   broadcast keep running in the background, with a notification for new
   live-chat messages.
5. Shows schedules the next countdown; Settings holds the connection,
   notifications, quiet hours, passcode/biometric lock and updates.

The app is signature- and checksum-verified on in-app updates; the shared key
is accepted only for backward compatibility and is being retired.

## Note
Tokens grant operator-reply + training-data access — keep them private.
Per-device tokens can be revoked individually from the admin Chat page.