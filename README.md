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

5. **Get the key file onto each phone**, then import it from inside the app — tap **Import
   Drive Key** on the main screen (shown automatically whenever no key is present yet), and
   pick the file in the system file browser. This works via a normal `content://` URI, so it
   doesn't matter whether the picker shows the file as `application/json`, `text/plain`, or
   something else a browser/email client guessed — filter is deliberately permissive.

   Get the key file onto the phone however's convenient (AirDrop-equivalent, email it to
   yourself, USB file copy, `adb push /path/to/key.json /sdcard/Download/`, etc.) — it just
   needs to be somewhere the file picker can browse to, e.g. Downloads. Once imported, feel
   free to delete it from wherever you staged it; it's now copied into the app's private
   storage.

   *(Note: `adb shell run-as` — the old way to place this file directly — only works on
   debuggable builds. A release build, which is what you should actually be running long-term,
   rejects it outright with "package not debuggable". The in-app import works on both.)*

   Repeat for Max's phone with the same key file — both installs share one service account.

## Build

Requires a JDK 17+ and the Android SDK (compileSdk/targetSdk 36, minSdk 26).

```bash
./gradlew assembleDebug     # quick iteration, debug-signed, run-as works for adb debugging
./gradlew assembleRelease   # what you should actually install long-term, see below
```

Debug output lands at `app/build/outputs/apk/debug/app-debug.apk`, release at
`app/build/outputs/apk/release/app-release.apk`.

### Release signing (one-time, before the first `assembleRelease`)

Not committed, not optional — `assembleRelease` silently fails without it. Generate a keystore
once and reuse it forever; **losing it or regenerating it means every future release build is
signed differently and Android will refuse to install it over the previous one** (a
differently-signed APK requires a full uninstall first, which wipes local app storage — see
"Reinstalling" below for why that's safe, but it's still an avoidable hassle). Back this file
up somewhere durable outside the repo.

```bash
keytool -genkeypair -v -keystore healthsync-release.jks -alias healthsync \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the project root (gitignored, alongside the `.jks` file):

```properties
storeFile=healthsync-release.jks
storePassword=<the password you set>
keyAlias=healthsync
keyPassword=<same password — PKCS12 keystores don't support separate store/key passwords>
```

## Install (sideload, both phones)

No Play Store distribution — this is a personal two-phone tool. Install the **release** build
day-to-day; debug is for iterating on the code itself.

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

(No `adb`/USB debugging on the target phone at all? Just copy the APK file onto the phone
however's convenient and tap it to install — Android will prompt to allow installing from
whatever app you opened it with.)

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

### Installing on Max's phone

Drive-side setup (service account, shared folder, placeholder CSVs) is shared and already
done — it's a one-time thing for the whole setup, not per-phone. Max's phone just needs:

1. Get `app-release.apk` onto the phone (however's convenient) and tap it to install.
2. Open the app → tap **Max** on the owner picker.
3. Grant Health Connect permissions when prompted (or tap **Sync Now** to trigger the prompt).
4. Get the same service-account key file onto the phone and tap **Import Drive Key** to pick
   it — same key file as Ozzy's phone, both installs share one service account.
5. Confirm **"Last sync succeeded"** appears.

No `adb` or USB debugging required anywhere in that list — steps 1 and 4 just need the two
files (APK, key) to reach the phone by whatever channel is easiest (send them directly, don't
use a public link for the key file, it's a credential).

### Reinstalling, or switching to a differently-signed build

Safe. A reinstall (or moving from debug-signed to release-signed, which Android treats as a
different app and requires a full uninstall first) wipes local app storage — owner choice,
sync cursor, cached Drive file ID. The next sync after that re-reads Health Connect's full
retention window from scratch, same as a true fresh install. That's expected, not a bug: before
re-uploading, `DriveUploader` reads the existing Drive file's `source_record_id` column and
filters out anything already present, so re-synced rows that were already uploaded get silently
dropped instead of duplicated. Verified directly: reinstalling and re-syncing on a real device
added exactly the rows that were genuinely new since the last sync, zero duplicates.

## What this app does not and cannot sync

Samsung's proprietary body-composition metrics (skeletal muscle mass, body fat %, BMI from the
watch's BIA sensor) and the continuous stress score **do not pass through Health Connect** —
they're cloud-only inside Samsung Health / Samsung Account and inaccessible to any third-party
app, including this one. This is a Samsung platform restriction, not a gap in this app; keep
exporting those manually. The app does not attempt to read Samsung Health's local database to
work around this.

## Verifying it worked

All of the below has actually been run end-to-end against a real phone and a real Drive
folder, not just reasoned about — see commit history for what broke and got fixed along the
way.

- Fresh install → pick owner → import key → grant permissions → sync → the CSV shows up in
  `File Archive/Health/Wearable_Data/<Owner>_Samsung_Health_Sync.csv` with rows shaped like
  `timestamp_utc,owner,metric,value,unit,source_record_id`, header included.
- A second sync with no new Health Connect data appends nothing and doesn't error.
- A second sync with new data appends only the new rows (cursor-based — see `SyncState.kt`).
- Reinstalling and re-syncing doesn't duplicate old rows — see "Reinstalling, or switching to a
  differently-signed build" above for how that's guaranteed even with the cursor gone.
