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

## Use
1. Open the app.
2. Enter the server URL: `https://amethyst2213.com/gallery`
3. Enter an **operator token** (create one on the admin Chat page →
   Operator tokens) or the legacy `GALLERY_CHAT_KEY`.
4. The inbox loads; open a conversation and reply. The background service
   notifies you of new member messages; offline replies queue and send
   automatically.

The app is signature- and checksum-verified on in-app updates; the shared key
is accepted only for backward compatibility and is being retired.

## Note
Tokens grant operator-reply + training-data access — keep them private.
Per-device tokens can be revoked individually from the admin Chat page.