package com.espaillat.healthsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.espaillat.healthsync.databinding.ActivityImportShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share target for Samsung Health Monitor's blood-pressure PDF export (Health Monitor ->
 * blood pressure history -> Export -> PDF -> share -> "Import to HealthSync"). This is the
 * only route into that data at all: Health Monitor's own BpContentProvider is locked behind a
 * signature|privileged permission no third-party app can ever hold (confirmed via a direct
 * SecurityException querying it), so unlike everything else this app reads, there is no
 * background-sync counterpart to this on its own -- it's manual, one file at a time.
 *
 * Parses via [SamsungHealthMonitorPdfImporter] and always shows a summary of what was found
 * before anything is staged -- a parsing mistake on health data is a real failure mode, not a
 * cosmetic one. Confirming here does *not* upload directly: it stages the parsed rows via
 * [PendingImports] and triggers [SyncWorker], which folds them into the same upload as Health
 * Connect data on its next run. That keeps exactly one code path in the app that actually talks
 * to Drive (auth, quota handling, dedup, status reporting) instead of a second one here that
 * could drift from it -- see PendingImports' doc for the full reasoning, including why
 * export-window overlap (Health Monitor's own export choices all overlap each other -- 1 week,
 * 2 weeks, last month, etc.) makes cross-import duplicate handling the normal case, not an edge
 * case.
 */
class ImportShareActivity : AppCompatActivity() {

    private data class ParseOutcome(
        val parsed: SamsungHealthMonitorPdfImporter.ParseResult,
        val rows: List<CsvRow>,
        val rawText: String,
    )

    private lateinit var binding: ActivityImportShareBinding
    private lateinit var syncState: SyncState
    private lateinit var driveUploader: DriveUploader
    private var pendingRows: List<CsvRow> = emptyList()
    private var awaitingSyncResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge is forced (not opt-in) once an app targets API 35+, so content draws
        // behind the system nav bar by default -- the "Add to Sync" button sitting flush against
        // the bottom edge would otherwise render underneath the gesture bar. Add the system
        // bars' own inset on top of the layout's existing padding (not replace it -- a straight
        // setPadding() here would silently drop the 24dp XML padding on whichever sides the
        // system reports a zero inset for) instead of guessing a fixed dp value that'd be wrong
        // on a different device/nav-bar style.
        val basePadding = binding.root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }

        syncState = SyncState(this)
        driveUploader = DriveUploader(this)

        binding.buttonDone.text = getString(R.string.button_add_to_sync)
        binding.buttonDone.isEnabled = false
        binding.buttonDone.setOnClickListener { onAddToSyncClicked() }

        // Same manual-work name/LiveData MainActivity observes -- this import's "sync now"
        // trigger and the main screen's own Sync Now button share one underlying work request,
        // so whichever one is showing gets the same real status, not two independent opinions
        // about whether a sync succeeded.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.MANUAL_WORK_NAME)
            .observe(this) { infos -> onWorkInfosChanged(infos) }

        handleIncomingShare()
    }

    private fun onWorkInfosChanged(infos: List<WorkInfo>) {
        if (!awaitingSyncResult) return
        val active = infos.any { !it.state.isFinished }
        if (active) return

        awaitingSyncResult = false
        val status = when (syncState.lastSyncStatus) {
            SyncStatus.SUCCESS -> "Sync succeeded."
            SyncStatus.ERROR -> "Sync failed: ${syncState.lastSyncError ?: "unknown error"}"
            SyncStatus.NEVER -> "Sync finished with no recorded status (unexpected)."
        }
        binding.textImportStatus.append("\n$status")
        binding.buttonDone.text = getString(R.string.button_done)
        binding.buttonDone.isEnabled = true
        binding.buttonDone.setOnClickListener { finish() }
    }

    private fun handleIncomingShare() {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "application/pdf") {
            binding.textImportStatus.text = getString(R.string.status_import_not_a_pdf_share)
            return
        }

        @Suppress("DEPRECATION")
        val streamUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (streamUri == null) {
            binding.textImportStatus.text = getString(R.string.status_import_no_file)
            return
        }

        binding.textImportStatus.text = getString(R.string.status_import_parsing)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val bytes = contentResolver.openInputStream(streamUri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not open the shared file")
                    val text = SamsungHealthMonitorPdfImporter.extractText(this@ImportShareActivity, bytes)
                    // The raw-extraction preview lives only in showParseResult's on-screen text
                    // below -- deliberately NOT also persisted to a file. This report contains a
                    // name/DOB/gender header plus every reading; writing it to external storage
                    // (as an earlier version of this activity did, for adb-pull convenience
                    // during development) would leave sensitive health data sitting on disk
                    // indefinitely with nothing to clean it up. The on-screen copy disappears
                    // with the activity, same as the rest of this screen's content.
                    val parsed = SamsungHealthMonitorPdfImporter.parse(text)
                    val owner = syncState.owner
                    val rows = if (owner != null) {
                        SamsungHealthMonitorPdfImporter.toCsvRows(parsed.readings, owner.label)
                    } else {
                        emptyList()
                    }
                    ParseOutcome(parsed, rows, text)
                }
                showParseResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "PDF import parse failed", e)
                binding.textImportStatus.text = getString(
                    R.string.status_import_parse_failed,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun showParseResult(outcome: ParseOutcome) {
        val (parsed, rows, rawText) = outcome
        pendingRows = rows

        val report = StringBuilder()
        report.appendLine("Readings found: ${parsed.readings.size}")
        parsed.readings.firstOrNull()?.let { first ->
            val last = parsed.readings.last()
            report.appendLine("Date range: ${first.date} → ${last.date}")
        }
        report.appendLine("Rows ready to stage: ${rows.size} (${parsed.readings.size} readings × 3 metrics each)")
        if (parsed.warnings.isNotEmpty()) {
            report.appendLine()
            report.appendLine("Warnings:")
            parsed.warnings.forEach { report.appendLine("- $it") }
        }
        if (syncState.owner == null) {
            report.appendLine()
            report.appendLine("No owner set for this phone yet -- open HealthSync first and pick an owner.")
        } else if (!driveUploader.hasServiceAccountKey()) {
            report.appendLine()
            report.appendLine(
                "No Drive key imported yet -- staging still works, but the sync this triggers " +
                    "will fail until a key's imported from the main HealthSync screen."
            )
        }

        report.appendLine()
        report.appendLine("--- parsed readings ---")
        parsed.readings.forEach {
            report.appendLine("${it.date} ${it.time}  ${it.systolicMmHg}/${it.diastolicMmHg} mmHg, pulse ${it.pulseBpm}")
        }

        // Kept deliberately, not just for the initial build-out: PDFBox-Android's text layout
        // for this export isn't something Samsung documents anywhere, and it already differed
        // once from what a desktop extraction library produced for the same PDF -- if Samsung
        // ever changes the export's structure again, this makes that visible immediately
        // (parsed readings looking wrong or empty, with the actual source right below to compare
        // against) rather than requiring a fresh adb pull to debug blind each time.
        report.appendLine()
        report.appendLine("--- raw extracted text ---")
        report.appendLine(rawText.take(4000))

        binding.textImportStatus.text = report.toString()
        binding.buttonDone.isEnabled = rows.isNotEmpty() && syncState.owner != null
    }

    private fun onAddToSyncClicked() {
        val rows = pendingRows
        if (rows.isEmpty() || syncState.owner == null) return

        PendingImports.stage(this, rows)
        awaitingSyncResult = true
        binding.buttonDone.isEnabled = false
        binding.textImportStatus.append("\n\nStaged ${rows.size} row(s). Syncing…")
        SyncWorker.enqueue(this)
    }

    companion object {
        private const val TAG = "HealthSyncImport"
    }
}
