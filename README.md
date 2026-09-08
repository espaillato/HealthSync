# HealthSync

Reads your phone's [Android Health Connect](https://health.google/health-connect-android/) data
and appends it to a long-format CSV in Google Drive, on a recurring background schedule. Install
it on as many phones as you want (one per person) — each one picks its own name on first launch
and writes to its own file in a shared Drive folder, so the data never collides.

**The core sync is Health Connect-only and works with any data source that writes into it** —
Samsung Health, Google Fit-compatible apps, a smart scale, whatever populates Health Connect on
your phone. Two extra, entirely optional features are Samsung-specific and only do anything if
you're on a Samsung device — see [Samsung Health full-data export import](#samsung-health-full-data-export-import-optional-samsung-only)
and [Blood pressure import](#blood-pressure-import-samsung-health-monitor-optional-samsung-only)
below. Skip both sections if you're not on Samsung Health; the rest of the app works exactly the
same without them.

No Play Store distribution — this is meant to be built and sideloaded yourself. See
[`Samsung_Health_Sync_App_Design_Doc.md`](Samsung_Health_Sync_App_Design_Doc.md) for the
original design brief this was built from (a useful read for the reasoning behind some choices,
but the code and this README are the current source of truth — that doc reflects where the
project started, not everything it grew into since).

## Set up with an AI coding agent

This repo ships a [`CLAUDE.md`](CLAUDE.md) written for exactly this: clone it, build it, and
install it on your phone(s) with the help of a coding agent (Claude Code or another agent that
reads project instructions files) rather than doing every step by hand. A prompt to get started:

> Clone `https://github.com/espaillato/HealthSync`, read `CLAUDE.md`, and set it up for me —
> build a signed release APK and install it on my Android phone(s) over adb, walking me through
> the one-time Google Drive and signing-key setup along the way.

Everything below still applies if you'd rather do it by hand.

## ⚠️ Step 0 — one-time Google Drive setup (do this before installing on any phone)

The app authenticates to Drive as a **service account**, not as your personal Google account. A
service account has no Drive storage of its own — if you skip this step, uploads will succeed
silently and land in the service account's own invisible Drive instead of your Drive.

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

2. **Create a destination folder** in your own Google Drive (if it doesn't already exist), named
   exactly `Wearable_Data`. It can live anywhere in your Drive — the app finds it by name, not by
   a fixed path.

3. **Share the `Wearable_Data` folder** with the service account's email as **Editor**
   (right-click the folder → Share). This is the step that makes the shared folder visible
   to the app — without it, nothing will appear in your Drive even if the app reports success.

4. **Create an empty placeholder CSV yourself** — as your own Google account, inside
   `Wearable_Data` — for each person's install, named exactly `<Name>_Samsung_Health_Sync.csv`
   (e.g. `Alex_Samsung_Health_Sync.csv` — see [Metrics synced](#metrics-synced) for why the
   filename keeps the `Samsung_Health_Sync` suffix regardless of your actual data source; it's
   just the app's fixed naming convention). This step is easy to skip and the app will look like
   it's working right up until it isn't: **service accounts have no storage quota of their own**,
   so they can't create files inside a folder shared with them as Editor — Drive rejects it
   with a `storageQuotaExceeded` 403, even though Editor access clearly allows writing. They
   *can*, however, update a file that already exists, since that storage is charged to the
   file's real owner (you), not to whoever's writing to it. Pre-creating the empty file
   sidesteps the whole problem: every sync from then on only ever updates, never creates. (The
   app surfaces this exact explanation in its status line if you hit it before reading this.)

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

   Repeat on every additional phone with the same key file — every install shares one service
   account.

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

## Install (sideload, each phone)

Install the **release** build day-to-day; debug is for iterating on the code itself.

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

(No `adb`/USB debugging on the target phone at all? Just copy the APK file onto the phone
however's convenient and tap it to install — Android will prompt to allow installing from
whatever app you opened it with.)

Android/Play Protect may warn about an unrecognized app on install — that's expected for a
sideloaded APK; dismiss it.

On first launch:

1. **Enter a name** — this is a one-time, permanent choice per install (there's no way to
   change it later short of clearing app data, by design). Whatever you type becomes the Drive
   filename and the `owner` column in every synced row.
2. Grant the requested Health Connect permissions — see [Metrics synced](#metrics-synced) below
   for the full list. Health Connect shows this as two screens, not one: the main per-category
   grant, then a second "Allow additional access" screen for two special permissions (access
   past data beyond the normal 30-day-from-grant window, and access data in the background so
   the nightly sync can actually read anything while the app isn't open) — grant both.
3. The app syncs on launch and registers a background sync that runs **once a day, around
   2am local time** (`SyncWorker.schedulePeriodicSync`) — not on every widget refresh, to
   avoid battery drain from frequent background wakeups. You can also tap **Sync Now** any
   time for an immediate sync. A home-screen widget, if you place one, is a status display
   only (last-synced time/error, tap to open) — it does not itself trigger a sync.

   Once a day is already generous for step/HR/sleep/exercise data; if you'd rather sync every
   2-3 days instead, bump `SYNC_INTERVAL_DAYS` in `SyncWorker.kt`.

Repeat for every additional phone (same APK, same Drive key file, a different name typed in at
step 1) — Drive-side setup (service account, shared folder, placeholder CSVs) only needs doing
once for the whole household, not per phone.

### Reinstalling, or switching to a differently-signed build

Safe. A reinstall (or moving from debug-signed to release-signed, which Android treats as a
different app and requires a full uninstall first) wipes local app storage — the entered name,
sync cursors, cached Drive file IDs. The next sync after that re-reads Health Connect's full
retention window from scratch, same as a true fresh install. That's expected, not a bug: before
re-uploading, `DriveUploader` reads the existing Drive file's `source_record_id` column and
filters out anything already present, so re-synced rows that were already uploaded get silently
dropped instead of duplicated. Verified directly: reinstalling and re-syncing on a real device
added exactly the rows that were genuinely new since the last sync, zero duplicates.

## Metrics synced

Current rule: if Health Connect exposes it behind a plain read permission and it reduces to a
scalar value per row, it's in; if it needs a real custom parser or an extra sensitive
permission beyond the normal grant, it's out. See `HealthConnectReader.kt` for the exact
mapping and reasoning inline.

**Aggregated to one row per local day** (sums — steps, distance, elevation gained, floors
climbed, active/total calories burned, wheelchair pushes, hydration, sleep session duration,
sleep stage minutes, exercise minutes) — this is a trend-tracking file (weeks/months/years),
not a live same-day dashboard, and per-record granularity for something like steps was ~80
rows/day of noise for that purpose. Sleep specifically buckets **noon-to-noon, not
midnight-to-midnight** (see `HealthConnectReader.sleepDayOf`) — a calendar-day boundary
routinely splits or misattributes a single night's sleep since sessions normally cross
midnight.

**Aggregated to daily min/avg/max, three rows per local day** (fluctuating readings — heart
rate, resting heart rate, HRV, oxygen saturation, respiratory rate, body/basal temperature,
blood glucose, VO2 max, speed, power, cycling cadence, steps cadence).

**Not aggregated — one row per record, point-in-time** (weight, height, body fat %, bone mass,
lean body mass, basal metabolic rate, **blood pressure**, **exercise sessions**) — a scale
reading, a blood pressure check, or a single workout, is a deliberate discrete event, not a
rate to smooth over a day. Exercise sessions carry the session's real Health Connect record ID,
title/notes (when present), and the min/avg/max heart rate recorded during that session's time
window, cross-referenced from the heart-rate stream separately.

A day (or sleep day) is only synced once it's actually over — see "Behavior note" below.

**Left out on purpose:**
- **Exercise GPS routes** — needs the separate, more sensitive `PERMISSION_READ_EXERCISE_ROUTES`
  consent rather than a normal read grant.
- **Full nutrition logging** — `NutritionRecord` has 30+ optional nutrient fields; doesn't
  reduce to a scalar-per-row without real custom mapping code.
- **Reproductive health** (menstruation, ovulation, sexual activity, etc.) — mechanically just
  as easy to add as anything else here, left out to keep the permission consent screen focused;
  add it yourself in `HealthConnectReader.kt`/`AndroidManifest.xml` if you want it.

### Behavior note: daily aggregation changes what "Sync Now" does

A day can only be aggregated once it's over, so `SyncWorker`'s `until` boundary is "start of
today, local time" — not "right now". A mid-day **Sync Now** tap will typically find nothing
new until the next calendar day begins, since today's steps/heart-rate/etc. aren't a finished
number yet. This pairs naturally with the existing ~2am nightly schedule (by then yesterday is
long complete), but it does mean the button stops being useful for "see today's live total" —
which matches this file's purpose (long-run trends) rather than live tracking, but is worth
knowing going in.

Timestamps on daily-aggregated rows use the local calendar date encoded as UTC midnight of that
same date string — deliberately not a true timezone conversion, so `timestamp_utc`'s date
portion always matches the day a human actually experienced rather than a UTC-shifted one.

## Samsung Health full-data export import (optional, Samsung-only)

Several Samsung Health metrics never reach Health Connect at all — Samsung computes them purely
internally (for its Energy Score / Sleep Score features) and never writes the underlying values
out through the platform's standard record types: **heart rate variability, respiratory rate,
stress score, advanced glycation end-products (AGE), post-exercise heart-rate recovery, skin
temperature, and custom exercise names**. The only way back in is Samsung Health's own full
personal-data export (**Samsung Health app → Settings → Download personal data**, not Samsung
Health Monitor's PDF share used for blood pressure below).

**How to use it:** request the export from within Samsung Health (it can take a while to
prepare and lands as a zip in your phone's Downloads); once you have it, extract it somewhere
the app can browse to, then tap **Connect Samsung Health Export Folder** in HealthSync and grant
folder access via the system picker. From then on, every sync scans that folder for a fresh
export and stages whatever new data it finds — safe to leave connected permanently; re-running
the export periodically (it's a full re-dump of your entire history every time, not
incremental) just gets picked up and deduplicated automatically via a per-metric cursor, same
as everything else in this app.

If you're not on Samsung, or don't care about these specific metrics, skip this entirely — the
rest of the app works exactly the same without it.

## Blood pressure import (Samsung Health Monitor, optional, Samsung-only)

Samsung Health Monitor — the separate app used for Galaxy Watch blood pressure readings — never
publishes that data to Health Connect at all, on any Samsung Health/Health Monitor version.
This isn't a settings toggle to go find: its `BpContentProvider` requires a
`signature|privileged` permission, confirmed by an actual `SecurityException` querying it
directly (`content://com.samsung.android.shealthmonitor.bp`). No third-party app can ever hold
that permission, sideloaded or not — so unlike everything else this app reads, there is no
background-sync path for this data. It has to be a manual export.

**How to use it:** in Samsung Health Monitor, open blood pressure history → **Export → PDF** →
share it → pick **"Import to HealthSync"** from the share sheet. HealthSync parses the PDF,
shows exactly what it found (every reading, plus any parsing warnings) before touching
anything, and only stages it for upload once you tap **Add to Sync** — nothing is written to
Drive without that explicit confirmation. Health Monitor's HTML export option isn't handled
(it wasn't available on the device/app version this was built against — see
`ImportShareActivity`'s manifest entry if that ever changes and it's worth adding).

**Under the hood:** the PDF is small and machine-generated with real embedded text, not a scan,
so it's parsed directly (`PDFBox-Android`, no OCR — avoids a misread-digit risk on top of the
parsing itself, which matters more here than anywhere else in this app). Confirming the import
doesn't upload directly: it stages the parsed rows locally (`PendingImports`) and triggers
`SyncWorker`, which folds them into the *same* upload as Health Connect data on its next run.
One code path talks to Drive in this app, not two that could quietly drift apart.

**One row per reading, not aggregated** — same point-in-time treatment as weight/height/etc.
above, producing its own `blood_pressure_pulse` metric alongside
`blood_pressure_systolic`/`blood_pressure_diastolic` — deliberately not folded into the general
`heart_rate` metric, since a pulse taken during a BP measurement isn't the same clinical
context as continuous or exercise heart rate. Because each reading is its own row with its own
timestamp, there's no "today isn't finished yet" concern the way there is for this file's
daily-aggregated metrics — nothing to protect against finalizing an incomplete bucket too
early, so every reading in an export uploads immediately, including today's.

**Duplicate handling across exports — the normal case, not an edge case:** Health Monitor's own
export windows (1 week, 2 weeks, last month, last 3 months, year to date) all overlap each
other, so re-exporting routinely re-covers days already synced. This is safe by design at two
levels: a reading already on Drive is skipped via a `source_record_id` synthesized from its own
date and time (`blood_pressure_<date>T<HHmm>#systolic`, etc. — deterministic, so the same
reading always produces the same ID), and a second, batch-level dedup in
`DriveUploader.appendRows` catches the case that check alone can't — two overlapping staged
imports both sitting un-synced at once, producing the same ID twice in a single upload.

**Workflow: exporting weekly or monthly instead of daily is fine.** A staged import isn't lost
if a sync doesn't happen right away or a sync attempt fails — it's only cleared after
successfully uploading, same "only advance state on success" rule the Health Connect sync
cursor follows. The one real thing to get right: **pick an export window at least as wide as
the gap since your last export.** The importer only ever sees what's inside the PDF you share —
sharing a "1 week" export once a month would leave a genuine ~3-week gap in the data, not a
duplicate, since nothing outside that window was ever in the file to begin with. Exporting
"last month" (or wider) on a monthly cadence keeps every day covered with room to spare.

## Verifying it worked

- Fresh install → enter a name → import key → grant permissions → sync → the CSV shows up in
  your `Wearable_Data` Drive folder as `<Name>_Samsung_Health_Sync.csv` with rows shaped like
  `timestamp_utc,owner,metric,value,unit,source_record_id`, header included.
- A second sync with no new Health Connect data appends nothing and doesn't error.
- A second sync with new data appends only the new rows (cursor-based — see `SyncState.kt`).
- Reinstalling and re-syncing doesn't duplicate old rows — see "Reinstalling, or switching to a
  differently-signed build" above for how that's guaranteed even with the cursor gone.

## License

[MIT](LICENSE).
