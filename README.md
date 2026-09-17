# Operator Chat (Android)

Thin Android client for the gallery site's operator chat. It connects to the
site's chat bridge webhooks with a Bearer token (`GALLERY_CHAT_KEY`) and lets
a human operator reply to member chat messages. **No AI logic and no API keys
live on the device** — the server does all AI work.

## Contract
See `docs/chat-android-contract.md` in the `gallery-site` repo.

- `GET  /webhooks/chat/config?conversation=ID` — mode (retrieval/finetuned/operator) + pending count
- `GET  /webhooks/chat/pending?conversation=ID` — member messages awaiting a reply
- `POST /webhooks/chat/reply` — send an operator reply (harvested into training data)

## Build
The GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug
APK automatically on every push to `main`. Download it from the workflow run's
**Artifacts** (Actions tab), or build a signed release locally:

```bash
gradle assembleRelease   # then sign with your keystore
```

Requires JDK 17 + Android SDK 35 (the CI provides both).

## Use
1. Open the app.
2. Enter the server URL: `https://amethyst2213.com/gallery`
3. Enter the bridge token (`GALLERY_CHAT_KEY` from the site's `.env`).
4. Enter a conversation ID and tap **Connect & start polling**.
5. New member messages appear; type a reply and tap **Send operator reply**.

The background service notifies you of new member messages while connected.

## Note
`GALLERY_CHAT_KEY` is the shared secret between the site and the operator app
— keep it private. It grants operator reply + training-data access.