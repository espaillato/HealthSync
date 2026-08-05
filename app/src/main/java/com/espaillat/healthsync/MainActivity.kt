package com.espaillat.healthsync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.espaillat.healthsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** The app's only real screen: Sync Now button, last-synced timestamp, status line. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncState: SyncState
    private lateinit var reader: HealthConnectReader

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.REQUIRED_PERMISSIONS)) {
            triggerSync()
        } else {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncState = SyncState(this)
        reader = HealthConnectReader(this)

        binding.buttonSyncNow.setOnClickListener { onSyncNowClicked(auto = false) }

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

        binding.textStatus.text = when (syncState.lastSyncStatus) {
            SyncStatus.SUCCESS -> getString(R.string.status_success)
            SyncStatus.ERROR -> getString(R.string.status_error, syncState.lastSyncError ?: "")
            SyncStatus.NEVER -> getString(R.string.status_never_synced)
        }
    }
}
