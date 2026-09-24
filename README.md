# Cache Cleaner

One-tap Android cache cleaner — clears other apps' caches without visiting
each app's settings page. Uses **Shizuku** (no root needed).

## How it works

- Integrates via `ShizukuProvider` from `dev.rikka.shizuku:provider` (the required client-library entry point; the `:api` artifact alone does NOT contain it).
- Talks to the hidden `IPackageManager` binder through Shizuku (shell uid).
- Reads cache sizes via hidden `getPackageSizeInfo()`.
- Clears caches via hidden `deleteApplicationCacheFiles()`.
- If a ROM blocks the hidden size query, cache sizes show as `?` — clearing
  still works.

## Requirements

- Android 8.0+ (API 26)
- [Shizuku](https://shizuku.rikka.app/) installed and running

## Build without a PC (GitHub Actions)

1. Create a GitHub repository and upload this project
   (or `git push` from Termux — see below).
2. The **Build APK** workflow runs automatically on every push, or manually
   from the Actions tab (**Run workflow**).
3. Open the finished workflow run and download the `cachecleaner-debug`
   artifact — it contains the signed debug APK.

## Develop entirely from Android (Termux)

```sh
pkg install git gh
git clone https://github.com/<you>/cachecleaner
cd cachecleaner

# edit sources with nano/vim, e.g.
nano app/src/main/java/com/example/cachecleaner/MainActivity.kt

git add -A
git commit -m "update"
git push

gh run watch          # wait for the CI build to finish
gh run download --name cachecleaner-debug
```

Then open the downloaded APK in your file manager and install it.

## Usage

1. Start Shizuku the way you normally do.
2. Open Cache Cleaner → **Grant via Shizuku** (once).
3. Tap **Refresh**, select apps (all are pre-selected), then **Clear**.

## Notes

- Debug APKs are signed with the CI debug key; if you reinstall over a
  locally-built copy Android may complain about signature mismatch —
  uninstall first.
- System apps are included in the list; be careful clearing system caches
  while they are running.

## Font

The UI uses Zen Dots. Download [ZenDots-Regular.ttf](https://github.com/google/fonts/raw/main/ofl/zendots/ZenDots-Regular.ttf)
and upload it via GitHub's **Add file -> Upload files** to
`app/src/main/res/font/zen_dots.ttf` (the build fails without it).
