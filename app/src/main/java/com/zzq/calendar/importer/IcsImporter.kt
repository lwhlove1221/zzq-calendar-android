package com.zzq.calendar.importer

import com.zzq.calendar.data.CalendarDbHelper
import com.zzq.calendar.data.EventStore
import com.zzq.calendar.data.StagingRow
import java.util.UUID

enum class ImportItemKind(val wireValue: String) {
    CREATED("created"),
    SKIPPED_DUPLICATE("skipped_duplicate"),
    CONFLICT("conflict"),
}

data class ImportItem(
    val kind: ImportItemKind,
    val line: Int,
    val title: String,
    val message: String = "",
)

enum class ImportStatus(val wireValue: String) {
    SUCCEEDED("succeeded"),
    PARTIAL("partial"),
    FAILED("failed"),
}

data class ImportPreview(
    val total: Int,
    val created: List<ParsedEvent>,
    val skipped: List<ImportItem>,
    val conflicts: List<ImportItem>,
    val failures: List<ParseFailure>,
    val warnings: List<String>,
    val fatal: ParseFatal? = null,
) {
    val isEmpty: Boolean get() = total == 0 && fatal == null
}

data class ImportOutcome(
    val status: ImportStatus,
    val total: Int,
    val created: Int,
    val skipped: Int,
    val conflicts: Int,
    val failed: Int,
    val failures: List<ParseFailure> = emptyList(),
    val fatal: ParseFatal? = null,
)

/**
 * PRD-03 §3.2 batch rules: >5% failures roll the whole batch back, otherwise the parsed
 * events are staged and moved into `events` in one transaction.
 */
class IcsImporter(
    private val helper: CalendarDbHelper,
    private val store: EventStore = EventStore(helper),
) {

    fun preview(bytes: ByteArray): ImportPreview {
        val parsed = IcsParser.parse(bytes)
        if (parsed.fatal != null) {
            return ImportPreview(
                total = parsed.total,
                created = emptyList(),
                skipped = emptyList(),
                conflicts = emptyList(),
                failures = parsed.failures,
                warnings = parsed.warnings,
                fatal = parsed.fatal,
            )
        }

        val index = store.fingerprintIndex()
        val created = mutableListOf<ParsedEvent>()
        val skipped = mutableListOf<ImportItem>()
        val conflicts = mutableListOf<ImportItem>()

        parsed.events.forEach { event ->
            val fingerprint = event.fingerprint
            val sameUid = if (event.hasUid) index.byUid[event.master.uid].orEmpty() else emptySet()
            when {
                event.hasUid && fingerprint in sameUid -> skipped += ImportItem(
                    ImportItemKind.SKIPPED_DUPLICATE,
                    event.line,
                    event.master.title,
                    "已存在同 UID 同指纹",
                )
                event.hasUid && sameUid.isNotEmpty() -> conflicts += ImportItem(
                    ImportItemKind.CONFLICT,
                    event.line,
                    event.master.title,
                    "同 UID 内容不同，默认不覆盖",
                )
                !event.hasUid && fingerprint in index.all -> skipped += ImportItem(
                    ImportItemKind.SKIPPED_DUPLICATE,
                    event.line,
                    event.master.title,
                    "已存在同指纹",
                )
                else -> created += event
            }
        }

        return ImportPreview(
            total = parsed.total,
            created = created,
            skipped = skipped,
            conflicts = conflicts,
            failures = parsed.failures,
            warnings = parsed.warnings,
            fatal = batchFatal(parsed),
        )
    }

    fun import(bytes: ByteArray): ImportOutcome {
        val preview = preview(bytes)
        val fatal = preview.fatal
        if (fatal != null) {
            return ImportOutcome(
                status = ImportStatus.FAILED,
                total = preview.total,
                created = 0,
                skipped = preview.skipped.size,
                conflicts = preview.conflicts.size,
                failed = preview.failures.size,
                failures = preview.failures,
                fatal = fatal,
            )
        }
        if (preview.created.isEmpty()) {
            return ImportOutcome(
                status = ImportStatus.SUCCEEDED,
                total = preview.total,
                created = 0,
                skipped = preview.skipped.size,
                conflicts = preview.conflicts.size,
                failed = preview.failures.size,
                failures = preview.failures,
            )
        }

        val batchId = UUID.randomUUID().toString()
        store.stageBatch(batchId, preview.created.map { StagingRow(it.line, it.sourceText) })

        val db = helper.writableDatabase
        var failure: Throwable? = null
        db.beginTransaction()
        try {
            store.readStaging(batchId).forEach { row ->
                val staged = IcsParser.parseStaged(row.payload)
                store.insertWithin(db, staged.master, staged.exceptions)
            }
            store.deleteStaging(db, batchId)
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            failure = error
        } finally {
            db.endTransaction()
        }

        if (failure != null) {
            runCatching { store.clearStaging() }
            return ImportOutcome(
                status = ImportStatus.FAILED,
                total = preview.total,
                created = 0,
                skipped = preview.skipped.size,
                conflicts = preview.conflicts.size,
                failed = preview.failures.size,
                failures = preview.failures,
                fatal = ParseFatal("E_PARSE", "写入失败，已回滚：${failure.message.orEmpty()}"),
            )
        }

        return ImportOutcome(
            status = if (preview.failures.isEmpty()) ImportStatus.SUCCEEDED else ImportStatus.PARTIAL,
            total = preview.total,
            created = preview.created.size,
            skipped = preview.skipped.size,
            conflicts = preview.conflicts.size,
            failed = preview.failures.size,
            failures = preview.failures,
        )
    }

    private fun batchFatal(parsed: ParseResult): ParseFatal? {
        if (parsed.total == 0 || parsed.failures.isEmpty()) return null
        if (parsed.failures.size * 100 > parsed.total * 5) {
            return ParseFatal(
                "E_PARSE",
                "失败 ${parsed.failures.size}/${parsed.total} 条，超过 5%，整批回滚",
            )
        }
        return null
    }
}
