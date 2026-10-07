# Changelog

## 0.5.0-pocket.1 (Tavern Pocket milestone 1)

Fork of [Sanitised/ST-android](https://github.com/Sanitised/ST-android) v0.5.0 for side-by-side install and Honor/MagicOS usability.

- Rebranded to **Tavern Pocket** (`com.bossogo.tavernpocket`) so it installs alongside upstream ST.
- Bundled SillyTavern bumped to **1.19.0**.
- In-app full-screen WebView shell (edge-to-edge, history back, file chooser, downloads, localhost-only cleartext, external links in the system browser, reconnect page). External browser remains as a fallback.
- Keep-alive: partial wake lock, START_STICKY + watchdog health restart, clear Stop in the notification and UI, OEM battery / app-launch guide (Honor/Huawei deep links with graceful fallback).
- Cleans half-installed extension folders after a failed install (upstream issues #12, #13, #20). Full extension updates remain out of scope.
- GitHub Actions builds an installable APK artifact without requiring upstream signing secrets.

## 0.5.0

- New feature to edit SillyTavern data folder with external file managers. Disabled by default.
- Added a hold-to-activate to the data removal button.

## 0.4.1

- Updated the bundled SillyTavern to 1.18.0 and Node.js to v24.18.0 LTS.

## 0.4.0

- Updated the bundled SillyTavern to 1.17.0, Node.js to v24.14.1.
- Added light, dark, and automatic theme selection. Initial implementation contributed by @Aritra1235.
- "Import Data" now supports user backups produced from the SillyTavern UI. Transfer your characters and chats easily.
- Minor UI improvements.
- Extension installation now works with SillyTavern 1.17+, but multiple related features, such as extension updates, are still broken.

## 0.3.1

- Added UI to set unrestricted battery use
- Improved archive handling during import/export

## 0.3.0

- Complete UI rework.
- Custom SillyTavern version installation from GitHub or ZIP archive.
- Opt-in automatic update checking.
- Automatically open browser when server is ready (optional).
- New icon.
- Script for one-line export of chats from SillyTavern on Termux.
- Multiple bugfixes and architecture improvements.

## 0.2.1

- Updated SillyTavern to version 1.16.0

## 0.2.0

- First public release.
