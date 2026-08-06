package com.espaillat.healthsync

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.espaillat.healthsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The app's only real screen: Sync Now button, last-synced timestamp, status line. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncState: SyncState
    private lateinit var reader: HealthConnectReader
    private lateinit var driveUploader: DriveUploader

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

        // Registers (or refreshes) the once-a-day background sync. Idempotent — safe to call
        // on every launch, see SyncWorker.schedulePeriodicSync.
        SyncWorker.schedulePeriodicSync(this)

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.MANUAL_WORK_NAME)
            .observe(this) { infos -> onWorkInfosChanged(infos) }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        onSyncNowClicked(auto = true)
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
     * Resets the cursor to null and re-syncs, so the next read starts from Health Connect's
     * earliest retained data again instead of wherever the cursor happened to be. Needed
     * whenever a newly-added metric type would otherwise only ever see data from the moment it
     * was added onward: the cursor is shared across every metric, so it had already advanced
     * past the new metric's entire history before that metric ever existed in the code. Safe to
     * re-run anytime -- DriveUploader's source_record_id dedup backstop means already-uploaded
     * days are silently skipped, not duplicated.
     */
    private fun onResyncHistoryClicked() {
        if (!HealthConnectReader.isAvailable(this)) {
            binding.textStatus.text = getString(R.string.status_health_connect_unavailable)
            return
        }
        lifecycleScope.launch {
            if (reader.hasAllPermissions()) {
                syncState.lastSyncCursor = null
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
        binding.textOwner.text = getString(R.string.label_owner, syncState.owner?.label ?: "?")

        val lastSync = syncState.lastSyncTimestamp
        binding.textLastSync.text = if (lastSync != null) {
            getString(R.string.label_last_sync, lastSync.toDisplayString())
        } else {
            getString(R.string.label_last_sync_never)
        }

        val dataThrough = syncState.lastSyncCursor
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
    }
}
