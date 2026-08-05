# HealthSync

Reads Samsung Health data (via Android's Health Connect) and pushes it to a shared Google
Drive CSV, on a recurring basis, from two sideloaded phones (Ozzy's and Max's). See
[`Samsung_Health_Sync_App_Design_Doc.md`](Samsung_Health_Sync_App_Design_Doc.md) for the full
design brief this was built from.

## ⚠️ Step 0 — one-time Google Drive setup (do this before installing on either phone)

The app authenticates to Drive as a **service account**, not as your personal Google
account. A service account has no Drive storage of its own — if you skip this step, uploads
will succeed silently and land in the service account's own invisible Drive instead of your
`File Archive`.

1. **Create the service account** (skip if you already have one for this purpose):
   - Go to [Google Cloud Console → IAM & Admin → Service Accounts](https://console.cloud.google.com/iam-admin/serviceaccounts).
   - Pick (or create) a project, click **Create Service Account**, give it any name
     (e.g. `healthsync-uploader`), and finish without granting it any project-level IAM roles
     (none are needed — Drive access is granted via folder sharing, not IAM).
   - Open the new service account → **Keys** tab → **Add Key** → **Create new key** → **JSON**.
     This downloads a `.json` key file — treat it like a password. Don't commit it anywhere.
   - Note the service account's email address, shown on its details page
     (looks like `healthsync-uploader@<project-id>.iam.gserviceaccount.com`).
   - If the Drive API isn't already enabled on that project, enable it under
     **APIs & Services → Library → Google Drive API**.

2. **Create the destination folder** in your own Google Drive (if it doesn't already exist):
   `File Archive/Health/Wearable_Data/`

3. **Share the `Wearable_Data` folder** with the service account's email as **Editor**
   (right-click the folder → Share). This is the step that makes the shared folder visible
   to the app — without it, nothing will appear in your Drive even if the app reports success.

4. **Create empty placeholder files yourself** — as your own Google account, inside
   `Wearable_Data` — named exactly `Ozzy_Samsung_Health_Sync.csv` and
   `Max_Samsung_Health_Sync.csv`. This step is easy to skip and the app will look like it's
   working right up until it isn't: **service accounts have no storage quota of their own**,
   so they can create files inside a folder shared with them as Editor — Drive rejects it
   with a `storageQuotaExceeded` 403, even though Editor access clearly allows writing. They
   *can*, however, update a file that already exists, since that storage is charged to the
   file's real owner (you), not to whoever's writing to it. Pre-creating empty files sidesteps
   the whole problem: every sync from then on only ever updates, never creates. (The app
   surfaces this exact explanation in its status line if you hit it before reading this.)

5. **Push the key onto each phone**, into the app's private storage (requires USB debugging
   enabled and `adb` installed — this only needs to happen once per phone, after the app is
   first installed):

   ```bash
   adb push /path/to/your-key.json /data/local/tmp/drive_service_account.json
   adb shell run-as com.espaillat.healthsync cp /data/local/tmp/drive_service_account.json files/drive_service_account.json
   adb shell rm /data/local/tmp/drive_service_account.json
   ```

   Repeat for Max's phone with the same key file — both installs share one service account.

## Build

Requires a JDK 17+ and the Android SDK (compileSdk/targetSdk 36, minSdk 26).

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install (sideload, both phones)

No Play Store distribution — this is a personal two-phone tool.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android/Play Protect may warn about an unrecognized app on install — that's expected for a
sideloaded APK; dismiss it.

On first launch:

1. Pick **Ozzy** or **Max** — this is a one-time, permanent choice per install (there's no way
   to change it later short of clearing app data, by design — see the design doc §2/§8).
2. Grant the requested Health Connect permissions (steps, heart rate, sleep, exercise).
3. The app syncs on launch and registers a background sync that runs **once a day, around
   2am local time** (`SyncWorker.schedulePeriodicSync`) — not on every widget refresh, to
   avoid battery drain from frequent background wakeups. You can also tap **Sync Now** any
   time for an immediate sync. A home-screen widget, if you place one, is a status display
   only (last-synced time/error, tap to open) — it does not itself trigger a sync.

   Once a day is already generous for step/HR/sleep/exercise data; if you'd rather sync every
   2-3 days instead, bump `SYNC_INTERVAL_DAYS` in `SyncWorker.kt`.

Each install writes to its own file — `Ozzy_Samsung_Health_Sync.csv` or
`Max_Samsung_Health_Sync.csv` — inside the shared `Wearable_Data` folder, so the two datasets
never collide.

## What this app does not and cannot sync

Samsung's proprietary body-composition metrics (skeletal muscle mass, body fat %, BMI from the
watch's BIA sensor) and the continuous stress score **do not pass through Health Connect** —
they're cloud-only inside Samsung Health / Samsung Account and inaccessible to any third-party
app, including this one. This is a Samsung platform restriction, not a gap in this app; keep
exporting those manually. The app does not attempt to read Samsung Health's local database to
work around this.

## Verifying it worked

- Fresh install → pick owner → grant permissions → tap Sync → the CSV shows up in
  `File Archive/Health/Wearable_Data/<Owner>_Samsung_Health_Sync.csv` with rows shaped like
  `timestamp_utc,owner,metric,value,unit,source_record_id`.
- A second sync with no new Health Connect data appends nothing and doesn't error.
- A second sync with new data appends only the new rows (cursor-based — see `SyncState.kt`).
- Reinstalling and re-syncing doesn't duplicate old rows (the cursor persists per-install in
  `SharedPreferences`, so a clean reinstall re-reads from Health Connect's retention window;
  `source_record_id` is included in every row as a dedup backstop if you ever need to reconcile
  the CSV by hand).
