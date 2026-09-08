# Agent playbook: setting up HealthSync

You're being asked to get [HealthSync](README.md) running for someone: build it and install it
on their Android phone(s). This file is the checklist — read [README.md](README.md) first for
*why* each step exists; this is the *order* to do them in and what to watch for.

Work through this interactively with the human. Several steps need their hands (physical taps
on the phone, a browser for Google Cloud Console) — don't try to script around those, just tell
them clearly what to do and wait.

## 0. Check prerequisites

- JDK 17+ (`java -version`)
- Android SDK with `adb` on PATH, or point `local.properties`'s `sdk.dir` at it
- A physical Android phone (or emulator with Health Connect, though real Health Connect data
  requires a real phone) with either USB debugging or wireless debugging enabled
- `git`

If any are missing, help the human install them before continuing — don't guess paths.

## 1. Google Drive service account (one-time, do before any install)

This is README's "Step 0" and it's entirely a human task in the Google Cloud Console / Drive
UI — walk them through it rather than attempting to automate it, unless they already have
`gcloud` authenticated and explicitly ask you to use it. Confirm with them after each of these
before moving on:

1. A service account exists and they've downloaded its JSON key file.
2. A `Wearable_Data` folder exists in their Drive and is shared with the service account's
   email as **Editor**.
3. An empty placeholder CSV exists in that folder for each person who'll use the app, named
   exactly `<Name>_Samsung_Health_Sync.csv`.

**Never** ask them to paste the key file's contents into chat, and never write it into the repo
— it's a credential. It only ever needs to exist as a local file they import through the app's
UI (Import Drive Key button) or push to the phone's Downloads folder for that.

## 2. Release signing key (one-time)

Check whether `keystore.properties` and the `.jks` it references already exist in the repo
root. **If they do, leave them alone** — regenerating either one makes every future release
build unable to install over an already-installed one without a full uninstall (data loss for
that phone). If they don't exist yet, generate them per README's "Release signing" section:

```bash
keytool -genkeypair -v -keystore healthsync-release.jks -alias healthsync \
  -keyalg RSA -keysize 2048 -validity 10000
```

then write `keystore.properties` (gitignored) with the `storeFile`/`storePassword`/`keyAlias`/
`keyPassword` it asked for. Tell the human to back the `.jks` file up somewhere durable outside
the repo — losing it is unrecoverable, not just inconvenient.

## 3. Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. If this fails because signing isn't
configured, that's step 2 not done yet, not a code problem.

## 4. Install on each phone

For each phone the human wants this on:

1. Connect it — `adb devices` should list it. For wireless debugging, pairing codes and ports
   are short-lived; if a connection attempt fails or a previous session's port is stale, ask
   for a fresh IP:port (and pairing code, if pairing for the first time) rather than retrying
   the old one.
2. Confirm the phone is unlocked before installing — a locked screen can silently swallow the
   install prompt. If HealthSync is already installed and might be mid-sync (rare on a brand
   new setup, real on a later update), don't force-stop it; let a sync in progress finish.
3. Install:
   ```bash
   adb -s <device> install -r app/build/outputs/apk/release/app-release.apk
   ```
4. Verify it actually landed at the version you just built:
   ```bash
   adb -s <device> shell dumpsys package com.espaillat.healthsync | grep -E "versionName|versionCode"
   ```

## 5. First-run steps the human has to do by hand, per phone

Physical taps — don't try to drive these via `adb shell input`, it's fragile and this is a
one-time, thirty-second task:

1. Open the app → type a name → **Continue**. This is permanent for that install (no in-app way
   to change it later — clearing app data is the only reset, and that also clears sync
   progress).
2. Tap **Import Drive Key**, pick the service-account JSON (get it onto the phone however's
   convenient first — `adb push key.json /sdcard/Download/` works over the same connection).
3. Tap **Sync Now**, grant the Health Connect permission screens (there are two — the main
   grant, then an "Allow additional access" screen for history + background access; both are
   needed).
4. Confirm the status line says **"Last sync succeeded"**.
5. Optional, Samsung phones only: if they want the [Samsung-specific extras](README.md#samsung-health-full-data-export-import-optional-samsung-only)
   (HRV, respiratory rate, stress, skin temp, exercise names, or the blood-pressure PDF
   import), those are separate opt-in steps documented in README — do them after confirming
   the core sync works, not before.

## Ongoing: making code changes

If you're asked to change something rather than just install it fresh:

- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every change that ships,
  even a small one — it's the only way to confirm on-device later that an install actually
  picked up the new build rather than silently no-opping over an identical version.
- Rebuild (`assembleRelease`), reinstall with `adb install -r`, and re-verify the version via
  `dumpsys` as in step 4 above — don't assume an install succeeded just because the command
  exited 0.
- Never force-stop the app while a sync might be running — a killed mid-write Drive upload can
  leave a CSV in an inconsistent state.
- Treat any technical claim about how Health Connect, the Samsung export format, or the Drive
  API actually behaves as worth verifying against real data or real library code before relying
  on it — this codebase has hit more than one platform quirk (page-atomic read failures,
  already-UTC timestamps despite a misleading offset field) that only came to light that way.

## Hard rules

- Never commit `*.jks`, `keystore.properties`, `local.properties`, or any Drive
  service-account JSON key. All four are already gitignored — keep it that way, and never
  suggest committing them "just for now."
- Never print a service-account key's contents into chat or logs.
