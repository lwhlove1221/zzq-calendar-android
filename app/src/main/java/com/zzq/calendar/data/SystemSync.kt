package com.zzq.calendar.data

import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.FingerprintV1

sealed class SyncResult {
    data class Applied(
        val created: Int,
        val updated: Int,
        val removed: Int,
        val conflicts: Int,
    ) : SyncResult()

    /** PRD-03 §3.3.12: a mass disappearance pauses all automatic delete/overwrite work. */
    data class Suspended(val missing: Int, val total: Int) : SyncResult()
}

/**
 * Mirrors ROM events into the local store and reconciles App-owned events by
 * `lastModified`. Mirrors always follow the system; App events never get
 * re-created after the system deleted them (they go to the 24h conflict list).
 */
class SystemSync(
    private val store: EventStore,
    private val port: SystemCalendarPort,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun sync(force: Boolean = false): SyncResult {
        val nowMillis = now()
        store.purgeConflictsBefore(nowMillis - CONFLICT_TTL_MILLIS)

        val systemById = mutableMapOf<Long, StoredEvent>()
        port.readEvents().forEach { event ->
            event.master.systemEventId?.let { systemById[it] = event }
        }

        val local = store.loadAll()
        val mirrorsById = local.filter { it.master.owner == EventMaster.OWNER_SYSTEM }
            .mapNotNull { event -> event.master.systemEventId?.let { it to event } }
            .toMap()
        val bound = local.filter {
            it.master.owner != EventMaster.OWNER_SYSTEM && it.master.systemEventId != null
        }

        val missingMirrors = mirrorsById.values.filter { event ->
            event.master.systemEventId?.let { systemById[it] == null } ?: false
        }
        val missingBound = bound.filter { event ->
            event.master.systemEventId?.let { systemById[it] == null } ?: false
        }

        // Mirror loss is judged by ratio; App-bound loss only by batch size, so deleting one
        // App event still reaches the conflict list instead of pausing the whole sync.
        if (!force && (resetTriggered(missingMirrors.size, mirrorsById.size) || missingBound.size > RESET_BATCH_LIMIT)) {
            return SyncResult.Suspended(
                missing = missingMirrors.size + missingBound.size,
                total = mirrorsById.size + bound.size,
            )
        }

        var removed = 0
        missingMirrors.forEach { mirror ->
            store.delete(mirror.master.id)
            removed++
        }

        var conflicts = 0
        missingBound.forEach { event ->
            val systemEventId = event.master.systemEventId ?: return@forEach
            if (force) {
                // Source reset: keep the App copy but stop pointing at a vanished system event.
                store.update(
                    event.master.copy(
                        systemEventId = null,
                        systemCalendarId = null,
                        lastSyncedModified = null,
                    ),
                    event.exceptions,
                )
            } else {
                store.upsertConflict(
                    SyncConflict(
                        id = "conflict-$systemEventId",
                        systemEventId = systemEventId,
                        title = event.master.title,
                        detail = CONFLICT_DELETED,
                        createdAt = nowMillis,
                    ),
                )
                // Stop pointing at the vanished event so the same deletion is not reported
                // again after the conflict expires; the App copy stays local.
                store.update(
                    event.master.copy(
                        systemEventId = null,
                        systemCalendarId = null,
                        lastSyncedModified = null,
                    ),
                    event.exceptions,
                )
                conflicts++
            }
        }

        var created = 0
        var updated = 0
        val boundIds = bound.mapNotNull { it.master.systemEventId }.toSet()
        systemById.forEach { (systemEventId, system) ->
            if (systemEventId in boundIds) return@forEach
            val existing = mirrorsById[systemEventId]
            if (existing == null) {
                store.insert(mirrorMaster(system, systemEventId, nowMillis), emptyList())
                created++
            } else if (existing.fingerprint != system.fingerprint) {
                store.update(
                    mirrorMaster(system, systemEventId, nowMillis).copy(
                        id = existing.master.id,
                        uid = existing.master.uid,
                        createdAt = existing.master.createdAt,
                        rev = existing.master.rev + 1,
                    ),
                    emptyList(),
                )
                updated++
            }
        }

        bound.forEach { event ->
            val systemEventId = event.master.systemEventId ?: return@forEach
            val system = systemById[systemEventId] ?: return@forEach
            val lastSynced = event.master.lastSyncedModified ?: 0L
            val systemModified = system.master.lastSyncedModified ?: 0L
            if (sameContent(event, system)) {
                // Content still matches: our own write echo, or nothing changed on either side.
                if (systemModified > 0) {
                    store.update(event.master.copy(lastSyncedModified = systemModified), event.exceptions)
                }
                return@forEach
            }

            val appChanged = event.master.updatedAt > lastSynced
            if (!appChanged) {
                store.update(adoptSystem(event, system, nowMillis), event.exceptions)
                updated++
                return@forEach
            }

            conflicts++
            // Providers without LAST_SYNCED give 0; without a system clock the safer choice is
            // keeping the user's App edit and surfacing the divergence.
            val systemWins = systemModified > 0 && systemModified > event.master.updatedAt
            if (systemWins) {
                store.update(adoptSystem(event, system, nowMillis), event.exceptions)
                updated++
            } else {
                // App wins: push it back so the two sides converge instead of conflicting forever.
                val pushed = runCatching { port.update(systemEventId, event.master) }.getOrNull()
                store.update(
                    event.master.copy(
                        lastSyncedModified = pushed ?: systemModified,
                        updatedAt = nowMillis,
                    ),
                    event.exceptions,
                )
            }
            store.upsertConflict(
                SyncConflict(
                    id = "conflict-$systemEventId",
                    systemEventId = systemEventId,
                    title = event.master.title,
                    detail = if (systemWins) CONFLICT_SYSTEM_WINS else CONFLICT_APP_WINS,
                    createdAt = nowMillis,
                ),
            )
        }

        return SyncResult.Applied(created, updated, removed, conflicts)
    }

    private fun mirrorMaster(system: StoredEvent, systemEventId: Long, nowMillis: Long): EventMaster =
        system.master.copy(
            id = "sys-$systemEventId",
            calendarId = "system-${system.master.systemCalendarId ?: 0}",
            owner = EventMaster.OWNER_SYSTEM,
            writeBack = false,
            systemEventId = systemEventId,
            lastSyncedModified = system.master.lastSyncedModified,
            updatedAt = nowMillis,
        )

    private fun adoptSystem(local: StoredEvent, system: StoredEvent, nowMillis: Long): EventMaster =
        local.master.copy(
            title = system.master.title,
            description = system.master.description,
            location = system.master.location,
            allDay = system.master.allDay,
            dtstartWall = system.master.dtstartWall,
            dtendWall = system.master.dtendWall,
            tzid = system.master.tzid,
            rruleRaw = system.master.rruleRaw,
            exdates = system.master.exdates,
            rdates = system.master.rdates,
            valarms = system.master.valarms,
            rev = local.master.rev + 1,
            updatedAt = nowMillis,
            lastSyncedModified = system.master.lastSyncedModified,
        )

    /** `calendar_id` is per-side bookkeeping, not content; ignore it when comparing. */
    private fun sameContent(local: StoredEvent, system: StoredEvent): Boolean {
        val comparable = system.master.copy(calendarId = local.master.calendarId)
        return FingerprintV1.sha256(comparable, system.exceptions) == local.fingerprint
    }

    private fun resetTriggered(missing: Int, total: Int): Boolean =
        total > 0 && (missing > RESET_BATCH_LIMIT || missing * 100 > total * RESET_RATIO_PERCENT)

    companion object {
        const val CONFLICT_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val RESET_RATIO_PERCENT = 30
        const val RESET_BATCH_LIMIT = 50
        const val CONFLICT_DELETED = "系统日历中已删除，保留本机副本 24 小时，未自动重写"
        const val CONFLICT_SYSTEM_WINS = "两边都改过，系统版本较新，已采用系统版本"
        const val CONFLICT_APP_WINS = "两边都改过，本机版本较新，已保留本机版本"
    }
}
