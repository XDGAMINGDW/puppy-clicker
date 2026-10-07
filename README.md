# Puppy Clicker

You press the paw, his phone buzzes, and his "available clicks" count goes up.

- `server/` – tiny Node.js server (holds the count, pushes live updates over WebSocket)
- `app/` – Android app (Kotlin + Jetpack Compose). One app, two roles:
  - **Sender** (you): pick an amount and press the paw.
  - **Receiver** (him): sees the count, gets a notification for each click. While the count is above 0 he can tap the paw to use one click, which plays `good boy.mp3` (bundled as `app/src/main/res/raw/good_boy.mp3`).
- `.github/workflows/release.yml` – builds a signed APK on every `v*` tag and publishes it as a GitHub Release. The app checks that release and offers the update.

## 1. Server

**Windows quick start:** double-click `run.bat`. It creates the secret codes, starts the server, and opens a free Cloudflare tunnel so the phones can reach it over the internet with HTTPS. No port forwarding is needed. It prints the server address to enter in the app. The address changes every time you restart `run.bat`, so both phones need to log in again with the new one.

**Manual setup:**

```bash
cd server
npm install
# Generate two different random codes, e.g.: node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"
SENDER_TOKEN=<your code> RECEIVER_TOKEN=<his code> npm start
```

The server listens on `127.0.0.1:8080`. The app only connects over **HTTPS**, so put it behind a reverse proxy. The easiest option is [Caddy](https://caddyserver.com/), which sets up HTTPS for you. This is the whole `Caddyfile`:

```
clicker.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
}
```

Run it with a process manager such as `pm2` or `systemd` so it keeps running. The count is saved to `server/data.json`.

## 2. Signing key (one time, keep it safe)

Every update has to be signed with the **same** key, or Android will refuse to install it.

```bash
keytool -genkeypair -v -keystore release.jks -alias puppy -keyalg RSA -keysize 2048 -validity 10000
```

Add these to your GitHub repo under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | output of `base64 -w0 release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `puppy` |
| `KEY_PASSWORD` | key password |

The repo has to be **public** so the app can read releases without a token. No secrets are stored in the code.

## 3. Release and auto-update

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions builds `puppy-clicker-v1.0.0.apk` and attaches it to a release.

- **First install:** open the release page on each phone, download the APK, and install it.
- **Later updates:** push a higher tag, like `v1.1.0`. The app checks for updates every time it opens. On his phone, the background service also checks every 6 hours and shows a notification. Tap **Update**, then **Install**. The first time, Android will ask you to allow installs from Puppy Clicker. After an update, the service restarts on its own.

## 4. Using the app

Open the app, enter the server address (`https://clicker.yourdomain.com`) and your secret code. The code decides the role: your code makes the phone the sender, and his code makes it the receiver.

On his phone, allow notifications. If clicks show up late, turn off battery optimization for Puppy Clicker (**Settings → Apps → Puppy Clicker → Battery → Unrestricted**).

## Offline and data loss

- **A phone is offline:** you can still use the paw on either phone. Clicks are saved on the phone and sent automatically when it's back online, even if the app is closed. Sending the same clicks twice never counts them twice.
- **His phone is offline when you send:** the server holds the clicks. When his phone reconnects, he gets one notification for everything he missed.
- **The server address changes:** log out and log in again with the new address and the **same** code. Clicks that haven't been sent yet are kept.
- **Server data is lost:** the server saves a daily copy to `server/backups/` (the last 14 days) and loads the newest good copy if `data.json` is missing or broken. If everything is gone, the phones put the totals back the next time they connect.

## Local development

Open the folder in Android Studio and run the app. Builds from Android Studio use the debug key. Uninstall them before you install a release APK, because the signatures won't match.
