# Samsung Health → Google Drive Sync App — Design Brief

**Purpose of this document:** Hand this to a Claude Code session to build the app. It specifies scope, architecture, and open decisions. Where I made a default choice instead of asking, it's marked "Default:" — change it if you want something else.

## 1. Problem

Two household members each wear a Samsung Galaxy Watch. Samsung Health syncs most metrics into Android's **Health Connect** API automatically (has since Samsung Health app v6.22.5, Oct 2022). I want one Android app, sideloaded via APK on both phones, that reads each phone's Health Connect data and pushes it to a shared Google Drive File Archive on a recurring basis — so it becomes queryable/analyzable without manual export.

Same codebase/APK on both phones — the app must identify which person's phone it's running on so the two datasets don't collide or merge.

I'm a developer and already have Google Drive API service-account credentials I use in existing Python scripts. Reuse those on both installs; do not build a fresh interactive OAuth flow.

## 2. Scope: what's automatable vs. not

**In scope (available via Health Connect):**
- Steps
- Heart rate (continuous + resting HR)
- Sleep sessions (stages, duration, score if exposed)
- Workouts/exercise sessions, if present

**Out of scope — flag, don't attempt:** Samsung's proprietary body-composition metrics (skeletal muscle mass, body fat %, BMI from the watch's BIA sensor) and continuous stress score **do not pass through Health Connect**. They're cloud-only inside Samsung Health / Samsung Account and inaccessible to third-party apps, including this one. No implementation approach fixes this — it's a Samsung platform restriction, not a technical gap in the app. I'll keep exporting those manually and periodically for now. Don't spend build time trying to work around it (e.g. scraping Samsung Health's local DB) — that's fragile and likely violates Samsung's terms.

## 3. Output

**Destination:** `File Archive/Health/Wearable_Data/` in Google Drive (create if it doesn't exist) — one shared folder, not per-person subfolders, so the owner distinction lives in the filename rather than the path.

**Owner identification:** On first launch, prompt for a name (a simple picker screen, not free text — Default: hardcode it to the two known household members for v1, revisit if this needs to generalize) and persist it in `SharedPreferences`. Everything downstream (filename, CSV rows) reads from this stored value. Default: no build flavors/variants needed for something this small — one APK, one first-run prompt, installed twice.

**Format — Default: a single rolling long-format CSV per owner**, `<Owner>_Samsung_Health_Sync.csv` (e.g. `Alex_Samsung_Health_Sync.csv`, `Sam_Samsung_Health_Sync.csv`), columns:

```
timestamp_utc, owner, metric, value, unit, source_record_id
```

The `owner` column is redundant with the filename but cheap insurance — if the two files ever get concatenated for analysis, rows stay self-identifying. One file per person, one upload/update call per sync, easy for me to read and pivot later regardless of how many metric types get added over time. Alternative considered and rejected as the default: per-metric CSVs or a Google Sheet — a Sheet adds OAuth-scope and API complexity (structured value-range writes, quota handling) for no real benefit here, since nothing needs live formulas or sharing/viewing by anyone but the two of us and me reading it programmatically. If per-metric files turn out easier to reason about during implementation, that's a fine substitution — just keep it to flat files, not Sheets.

**Dedup/incremental sync:** Store the last successful sync's end-timestamp locally (SharedPreferences, per-device so this is naturally already per-owner). Each sync only pulls Health Connect records newer than that cursor, and appends rather than rewrites. On any failure (network, auth, Drive API error), don't advance the cursor — next sync retries the same window.

**Auth to Drive:** Reuse my existing service-account JSON key on both installs. Store it in the app's private internal storage (not hardcoded into source, not committed anywhere). One-time setup requirement: **share the `Wearable_Data` folder with the service account's email as Editor** — a service account has no Drive storage of its own, so without this share, uploads succeed but land invisibly in the service account's own Drive instead of the File Archive. Put this as a bolded step-zero in the app's README. Each install writes to its own filename within the shared folder, so no write-conflict handling is needed between the two phones.

## 4. Sync triggers

No single mechanism needs to be perfect — the three below are complementary, not exclusive:

1. **On app launch** — sync automatically whenever the app is opened.
2. **Manual button** — a "Sync Now" button in the main (only) screen, with a visible last-synced timestamp and last-sync status (success/error).
3. **Home-screen widget (optional, nice-to-have)** — an `AppWidgetProvider` that triggers a background sync on its periodic update cycle. Android's minimum widget update interval is 30 minutes (`updatePeriodicMillis`), enforced by the OS — don't try to go faster. The widget's `onUpdate` should hand off to `WorkManager` (a one-time `OneTimeWorkRequest`) rather than doing network I/O directly in the broadcast receiver, to avoid ANRs.

No foreground service, no exact-alarm scheduling, no fighting Doze/battery-optimization — none of that is needed given trigger #3 already rides on the OS's own widget update cycle and #1/#2 are user-initiated. Keep this simple; don't over-engineer background reliability that wasn't asked for.

## 5. Architecture

- **Language/build:** Kotlin, standard Gradle project, single module.
- **Min SDK:** whatever Health Connect requires (currently API 26+ for the Health Connect client library, but Health Connect as a system feature needs Android 14+ or the standalone Health Connect app on 26–33 — check current requirements when building, this shifts with OS versions).
- **Health Connect access:** `androidx.health.connect:connect-client`. Request read permissions for steps, heart rate, sleep, exercise at runtime via the standard Health Connect permission flow.
- **Drive access:** Google Drive API v3 via a service-account-authenticated client (`google-api-client` / `google-auth-library-java`), `files.create` / `files.update` (media upload) against the known file ID once the CSV exists, or `files.list` by name+parent to find it on first run.
- **Background work:** `WorkManager` for the widget-triggered sync path.
- **Local state:** `SharedPreferences` for owner identity, last-sync cursor, last-sync status/timestamp, cached Drive file ID.
- **UI:** first-run owner picker, then one main screen — sync button, last-synced timestamp, status line, permission-grant prompt if Health Connect access hasn't been granted yet.

## 6. Suggested project structure

```
app/
  src/main/java/.../
    HealthConnectReader.kt      // reads steps/HR/sleep/exercise since cursor
    DriveUploader.kt            // auth + CSV append/upload, targets <owner>_Samsung_Health_Sync.csv
    SyncWorker.kt                // WorkManager worker, calls Reader -> Uploader
    SyncWidgetProvider.kt        // AppWidgetProvider, enqueues SyncWorker
    MainActivity.kt              // manual button, status display, permission flow
    OwnerPickerActivity.kt       // first-run owner picker
    SyncState.kt                 // SharedPreferences wrapper (owner, cursor, status)
  src/main/res/xml/
    widget_info.xml              // updatePeriodicMillis config
  src/main/AndroidManifest.xml   // Health Connect permissions, INTERNET
```

## 7. Permissions needed

- Health Connect: `android.permission.health.READ_STEPS`, `READ_HEART_RATE`, `READ_SLEEP`, `READ_EXERCISE` (adjust to whatever's actually available/needed).
- `android.permission.INTERNET` for Drive API calls.
- No storage permissions needed if the service-account key ships in app-private storage (scoped storage handles this fine).

## 8. Explicitly deferred / non-goals for v1

- No Play Store distribution — sideloaded APK only, no auto-update, Play Protect may warn on install (expected, dismiss).
- No support for more than the two initial owners for v1 — hardcode the picker to those two rather than building general multi-user account management. (Later lifted — see README; the picker is free text now.)
- No body-composition/stress workaround (see Section 2).
- No historical backfill beyond what Health Connect currently retains on-device — check Health Connect's retention window before assuming full history is available; it isn't infinite.

## 9. Definition of done

- Fresh install on either phone → pick owner → grant Health Connect permissions → tap Sync → CSV appears in `File Archive/Health/Wearable_Data/<Owner>_Samsung_Health_Sync.csv` with correctly typed/timestamped rows, `owner` column matching the picked value.
- Installing on both phones produces two distinct, correctly-named files with no cross-writes or overwritten data.
- Second sync (no new data) appends nothing, doesn't error.
- Second sync (new data since last run) appends only the new rows.
- Widget placed on home screen triggers a sync within one update cycle without opening the app.
- Killing/reinstalling the app and re-running sync doesn't duplicate rows already in the Drive file (cursor logic holds, or dedup on `source_record_id` as a backstop).
