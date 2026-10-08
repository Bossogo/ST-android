# Tavern Pocket

Local [SillyTavern](https://github.com/SillyTavern/SillyTavern) runner for Android (arm64, Android 8.0+), based on [Sanitised/ST-android](https://github.com/Sanitised/ST-android).

This fork is **not affiliated with or endorsed by** SillyTavern or the upstream ST-android project. It is intended for basic on-device chatting. Extensions are only partially supported.

## Credits

- Upstream Android shell: [Sanitised/ST-android](https://github.com/Sanitised/ST-android) (AGPL-3.0)
- Bundled chat UI/server: [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) (AGPL-3.0), currently the `1.19.0` release
- Bundled runtime: [Node.js](https://github.com/nodejs/node) with Termux-derived Android patches

All original copyright notices, license texts, and legal assets from those projects are retained.

## Privacy

- No telemetry of any kind.
- Unlike Termux, the app works in Private Space/Secure Folder/Secondary profiles.
- Minimal network calls: opt-in GitHub release checks, npm installs, and GitHub downloads for custom ST versions. All other traffic comes from SillyTavern itself.
- All chats, characters, settings stay local unless you decide to export them manually and share with others.
- Bundles SillyTavern with a small install-time cleanup patch for half-installed extension folders (see below); Node.js includes the minimal Android patches from upstream ST-android.

## Features (this fork — milestone 1)

- Rebranded so it can install **alongside** upstream ST-android (`com.bossogo.tavernpocket`)
- Bundled SillyTavern **1.19.0**
- In-app full-screen WebView shell (edge-to-edge, back history, file chooser, downloads, localhost cleartext only, external links in the system browser, reconnect page). External browser remains available as a fallback.
- Keep-alive helpers for aggressive OEMs (partial wake lock, START_STICKY + watchdog restart, clear Stop action, MagicOS/Honor app-launch deep links)
- Cleans incomplete extension folders after a failed install so reinstall works

## Installation (Honor / MagicOS)

1. Open the GitHub Actions run for your branch/PR and download the **`tavern-pocket-apk`** artifact (or build locally — see below).
2. On the phone: **Settings → Security → More settings → Install apps from external sources** (wording varies on MagicOS) and allow your Files / browser app.
3. Open the APK and install. It will not replace upstream ST-android if that is already installed.
4. After first start, open **Background survival guide** (or Settings) and set battery to unrestricted + enable Auto-launch / App launch for Tavern Pocket.

## Transferring data from SillyTavern on Termux/PC

The app accepts `.tar.gz`, `.tar`, and `.zip` archives. The file format is detected automatically.

The app supports two archive types: full backups exported from this app and SillyTavern user backups.

Full backups produced by this app save more information and are generally recommended, especially for reinstalls.

### Use SillyTavern user backups for data transfer

In your old installation of SillyTavern, press **User Settings**, **Account**, **Download Backup**.

Then stop the server in the app, tap **Manage ST**, **Import Data** and select the backup archive (for example, `default-user-20260303-122334.zip`).

This method is the easiest, and will import all your chats, characters, and other user data. It won't work properly for multi-user setups, and it won't transfer the server config.

### Quick full backup for data transfer (Termux or Linux)

Run this one-liner against the upstream export helper:

```bash
bash <(curl -sSf https://raw.githubusercontent.com/Sanitised/ST-android/master/tools/export_to_st_android.sh)
```
If your SillyTavern folder is not in a standard location, first do `cd ./my-sillytavern`.

Then stop the server in the app, tap **Manage ST**, **Import Data** and select the backup archive (for example, `st_backup.tar.gz`).

### Making full data backup manually for data transfer

The archive must have this structure:

```
st_backup/
├── config.yaml
└── data/
```

```bash
mkdir st_backup
cp /path/to/sillytavern/config.yaml st_backup/
cp -r /path/to/sillytavern/data st_backup/
tar -czf st_backup.tar.gz st_backup/
```

On Termux, copy the archive to Downloads so the app can reach it:

```bash
termux-setup-storage   # one-time permission grant
cp st_backup.tar.gz ~/storage/downloads/
```

Then stop the server in the app, tap **Manage ST**, **Import Data** and select the backup archive (for example, `st_backup.tar.gz`).

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## Build (Docker)

Prereqs: Docker installed (plus Git for cloning the repo). Tested only on Linux.

```bash
git clone https://github.com/Bossogo/ST-android
cd ST-android
git submodule update --init --recursive
./ci/scripts/build_apk_docker.sh
```

The first build takes a long time while compiling Node from scratch. Subsequent builds are much faster.

Output:
- `out/TavernPocket-*-debug.apk` (or `-release.apk` when signing secrets are provided)
- Also under `app/build/outputs/apk/...`

## License

AGPL-3.0. See [LICENSE.txt](LICENSE.txt) and the in-app Legal screen.
