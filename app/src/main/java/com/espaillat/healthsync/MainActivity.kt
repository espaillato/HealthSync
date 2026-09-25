package com.espaillat.healthsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.espaillat.healthsync.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The app's only real screen: Sync Now button, last-synced timestamp, status line. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncState: SyncState
    private lateinit var reader: HealthConnectReader
    private lateinit var driveUploader: DriveUploader
    private var statusPollJob: Job? = null

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.REQUIRED_PERMISSIONS)) {
            triggerSync()
        } else {
            refreshUi()
        }
    }

    // Release builds aren't debuggable, so `adb shell run-as ... cp` (the dev-workflow way of
    // placing the service-account key) is blocked by the OS outright -- this is the real,
    // user-facing way to get the key into app-private storage: pick the downloaded key file
    // from wherever it landed on the phone (Downloads, an email attachment, etc.) and the app
    // copies its bytes into its own storage via the returned content:// URI. Works identically
    // whether the file's detected MIME type is application/json, text/plain, or something else
    // a browser/email client guessed, so the filter here is deliberately permissive ("*/*")
    // rather than risking hiding the very file the user is looking for.
    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importServiceAccountKey(uri)
    }

    // Samsung Health's own full-data export lands in a fixed Downloads subfolder rather than
    // being shared to a specific app, so this is a one-time folder grant (Storage Access
    // Framework) instead of a share-target flow -- see SamsungHealthExportImporter's doc for why
    // this is the only way to reach HRV/respiratory rate at all. The permission persisted here
    // survives app restarts and reboots on its own; only the chosen folder's identity is stored.
    private val pickExportFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            syncState.samsungHealthExportFolderUri = uri.toString()
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncState = SyncState(this)
        reader = HealthConnectReader(this)
        driveUploader = DriveUploader(this)

        binding.buttonSyncNow.setOnClickListener { onSyncNowClicked(auto = false) }
        binding.buttonImportKey.setOnClickListener { importKey.launch(arrayOf("*/*")) }
        binding.buttonResyncHistory.setOnClickListener { onResyncHistoryClicked() }
        binding.buttonConnectExportFolder.setOnClickListener { pickExportFolder.launch(null) }

        // Registers (or refreshes) the once-a-day background sync. Idempotent — safe to call
        // on every launch, see SyncWorker.schedulePeriodicSync.
        SyncWorker.schedulePeriodicSync(this)

        // Kept as a low-latency bonus signal, not the sole source of truth for the status text
        // anymore -- see onResume's poll loop below for why. Confirmed live, 2026-08-23: this
        // LiveData observer does not reliably push every state transition while the app sits
        // open and the user never triggers a fresh resume -- a sync could reach genuine SUCCESS
        // (confirmed via WorkManager's own state directly) while this screen kept showing
        // "Syncing…" indefinitely, only correcting itself once the user locked/unlocked the
        // screen (an onResume) rather than on its own. Left in place since it usually does fire
        // promptly and costs nothing extra when it does -- the poll loop is what guarantees
        // correctness regardless of whether this fires or not.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.MANUAL_WORK_NAME)
            .observe(this) { infos -> onWorkInfosChanged(infos) }
    }

    override fun onResume() {
        super.onResume()
        // Deliberately NOT a plain refreshUi() call here -- that was a real bug (confirmed live,
        // 2026-08-23): it paints whatever sync result was persisted *before* this resume, with
        // no idea whether a sync is actively running again right now (e.g. the screen was locked
        // and unlocked while one was still mid-flight), so it could show a stale "last sync
        // succeeded" from an earlier run while a brand new one was still genuinely in progress.
        // Explicitly querying WorkManager's *current* state here and routing it through the
        // exact same onWorkInfosChanged() decision the LiveData observer uses means onResume's
        // very first paint is already correct, not dependent on that observer's own timing.
        pollWorkInfo()
        onSyncNowClicked(auto = true)

        // The LiveData observer above is a bonus, not a guarantee (see onCreate's doc) -- this
        // loop is the actual fix for the screen getting stuck showing "Syncing…" after a sync
        // has genuinely finished while the app just sits open with no fresh resume to trigger a
        // recheck. Cheap (a local WorkManager/Room query, not network), so a few-second cadence
        // costs nothing meaningful; cancelled in onPause so it never runs while backgrounded.
        statusPollJob?.cancel()
        statusPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(4_000)
                pollWorkInfo()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusPollJob?.cancel()
    }

    private fun pollWorkInfo() {
        val workInfoFuture = WorkManager.getInstance(this).getWorkInfosForUniqueWork(SyncWorker.MANUAL_WORK_NAME)
        workInfoFuture.addListener({ onWorkInfosChanged(workInfoFuture.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun importServiceAccountKey(uri: Uri) {
        try {
            val destination = File(filesDir, DriveUploader.SERVICE_ACCOUNT_KEY_FILENAME)
            val opened = contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file")
            opened.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            refreshUi()
            onSyncNowClicked(auto = false)
        } catch (e: Exception) {
            binding.textStatus.text = getString(R.string.status_key_import_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onSyncNowClicked(auto: Boolean) {
        if (!HealthConnectReader.isAvailable(this)) {
            binding.textStatus.text = getString(R.string.status_health_connect_unavailable)
            return
        }
        lifecycleScope.launch {
            when {
                reader.hasAllPermissions() -> triggerSync()
                !auto -> requestPermissions.launch(HealthConnectReader.REQUIRED_PERMISSIONS)
                else -> binding.textStatus.text = getString(R.string.status_permissions_needed)
            }
        }
    }

    private fun triggerSync() {
        SyncWorker.enqueue(this)
    }

    /**
     * Resets every Health Connect record type's own cursor (see [SyncState.healthConnectCursor])
     * and re-syncs, so the next read starts from each one's earliest retained data again instead
     * of wherever its cursor happened to be. Needed whenever a newly-added metric would
     * otherwise only ever see data from the moment it was added onward: its own cursor wouldn't
     * exist yet, but a *different*, already-cursored metric type reading fine wouldn't trigger a
     * backfill for it either now that cursors are independent per record type (changed
     * 2026-09-07) -- this button is the explicit way to force that backfill for everything at
     * once. Safe to re-run anytime -- DriveUploader's source_record_id dedup backstop means
     * already-uploaded days are silently skipped, not duplicated.
     */
    private fun onResyncHistoryClicked() {
        if (!HealthConnectReader.isAvailable(this)) {
            binding.textStatus.text = getString(R.string.status_health_connect_unavailable)
            return
        }
        lifecycleScope.launch {
            if (reader.hasAllPermissions()) {
                syncState.resetAllHealthConnectCursors()
                binding.textStatus.text = getString(R.string.status_resyncing_history)
                triggerSync()
            } else {
                requestPermissions.launch(HealthConnectReader.REQUIRED_PERMISSIONS)
            }
        }
    }

    private fun onWorkInfosChanged(infos: List<WorkInfo>) {
        val active = infos.any { !it.state.isFinished }
        if (active) {
            binding.textStatus.text = getString(R.string.status_syncing)
        } else {
            refreshUi()
        }
    }

    private fun refreshUi() {
        binding.textOwner.text = getString(R.string.label_owner, syncState.owner ?: "?")

        val lastSync = syncState.lastSyncTimestamp
        binding.textLastSync.text = if (lastSync != null) {
            getString(R.string.label_last_sync, lastSync.toDisplayString())
        } else {
            getString(R.string.label_last_sync_never)
        }

        // The most conservative reading across every Health Connect record type's own cursor --
        // see SyncState.minHealthConnectCursor's doc for why there's no single shared cursor to
        // read this off directly anymore.
        val dataThrough = syncState.minHealthConnectCursor()
        binding.textDataThrough.text = if (dataThrough != null) {
            getString(R.string.label_data_through, dataThrough.toDisplayString())
        } else {
            getString(R.string.label_data_through_none)
        }

        binding.textStatus.text = when (syncState.lastSyncStatus) {
            SyncStatus.SUCCESS -> getString(R.string.status_success)
            SyncStatus.ERROR -> getString(R.string.status_error, syncState.lastSyncError ?: "")
            SyncStatus.NEVER -> getString(R.string.status_never_synced)
        }

        binding.buttonImportKey.visibility = if (driveUploader.hasServiceAccountKey()) View.GONE else View.VISIBLE
        binding.buttonConnectExportFolder.visibility =
            if (syncState.samsungHealthExportFolderUri != null) View.GONE else View.VISIBLE

        // Persistent per-source problem list -- see the layout comment on text_source_warnings
        // for why this is separate from textStatus above. Sorted by source name purely so the
        // list doesn't reorder itself between refreshes for no reason.
        val sourceErrors = syncState.allSourceErrors()
        if (sourceErrors.isEmpty()) {
            binding.textSourceWarnings.visibility = View.GONE
        } else {
            binding.textSourceWarnings.visibility = View.VISIBLE
            binding.textSourceWarnings.text = getString(R.string.label_source_warnings_header) + "\n\n" +
                sourceErrors.entries.sortedBy { it.key }.joinToString("\n\n") { (source, message) -> "⚠ $source\n$message" }
        }
    }
}
