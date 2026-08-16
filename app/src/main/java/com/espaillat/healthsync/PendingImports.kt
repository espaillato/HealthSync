package com.espaillat.healthsync

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Staging area for CsvRows parsed outside the normal Health Connect read path -- currently just
 * the Samsung Health Monitor PDF import (see ImportShareActivity / SamsungHealthMonitorPdfImporter).
 *
 * Rows land here the moment a share is parsed and confirmed, but nothing talks to Drive from
 * here directly: SyncWorker picks staged rows up as an additional data source alongside Health
 * Connect on its next run and folds them into the same [DriveUploader.appendRows] call, so there
 * is exactly one place in the app that actually authenticates, handles quota, dedups, and
 * reports sync status -- not a second upload path that could quietly drift from it.
 *
 * A staged file is only deleted after the sync that consumed it actually succeeds -- the same
 * "only advance state on success" rule the sync cursor itself follows -- so a failed sync leaves
 * the import staged for the next attempt instead of losing it. [snapshot] and [clear] are split
 * for exactly this reason: take a snapshot of what's staged *before* a sync attempt, only clear
 * that same snapshot's files if the attempt succeeds, so anything staged mid-attempt (unlikely
 * given WorkManager's own serialization, but not impossible) isn't deleted before it's ever
 * actually been uploaded.
 */
object PendingImports {

    /** Writes [rows] to a new staged file, one CsvRow line each. Safe to call more than once
     *  before a sync runs -- each call gets its own file, and [snapshot] picks up all of them. */
    fun stage(context: Context, rows: List<CsvRow>) {
        if (rows.isEmpty()) return
        val file = File(pendingDir(context), "import_${System.currentTimeMillis()}.csv")
        file.writeText(rows.joinToString("\n") { it.toCsvLine() } + "\n")
    }

    /** Every currently-staged file paired with its parsed rows. Unparseable lines are logged
     *  and skipped rather than failing the whole file -- a single corrupted line shouldn't cost
     *  every other reading in the same staged import. */
    fun snapshot(context: Context): List<Pair<File, List<CsvRow>>> {
        val files = pendingDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            ?: return emptyList()

        return files.map { file ->
            val rows = file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                CsvRow.fromCsvLine(line) ?: run {
                    Log.w(TAG, "Skipped unparseable staged line in ${file.name}: $line")
                    null
                }
            }
            file to rows
        }
    }

    /** Deletes exactly the files from a prior [snapshot] -- only call after their rows have
     *  actually been uploaded successfully. */
    fun clear(files: List<File>) {
        files.forEach { it.delete() }
    }

    private fun pendingDir(context: Context): File =
        File(context.filesDir, "pending_imports").apply { mkdirs() }

    private const val TAG = "HealthSyncPendingImports"
}
