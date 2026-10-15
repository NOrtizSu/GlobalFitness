package com.enjoyyourtime.globalfitness.cloud

import java.security.MessageDigest

/**
 * Pure merge of the phone's data with the cloud copy (no Android, no Firebase), unit-tested.
 *
 * - Profile and settings: last writer wins by edit time (ties go to the cloud copy).
 * - Measurements and devices: union by id. A three-way compare against the last agreed [SyncBase]
 *   tells which side changed; when both changed, measurements keep the known impedance.
 * - Deletions are tombstones. A tombstone wins over a live version that is not newer than it.
 * - Something that disappeared from the phone since the last sync becomes a tombstone, but only
 *   when the base is trusted (same dataset, phone not suddenly empty), so a reinstall or a wiped
 *   database restores from the cloud instead of deleting it.
 */
object SyncMerge {

    class Plan internal constructor(
        /** Apply these on the phone. */
        val localChanges: RemoteChanges,
        /** Write these to the cloud. */
        val uploads: List<CloudWrite>,
        internal val measurementTargets: Map<String, MeasurementRecord>,
        internal val deviceTargets: Map<String, DeviceRecord>,
    ) {
        val isNoOp: Boolean get() = localChanges.isEmpty && uploads.isEmpty()
    }

    // ---- Content hashes (what counts as "changed") ---------------------------------------------

    /** Only the mutable fields: the id already encodes timestamp and weight. */
    fun hashOf(m: MeasurementRecord): String = "${m.impedance ?: "-"}|${m.source}"

    fun hashOf(d: DeviceRecord): String =
        sha1(listOf(d.driverId, d.name) + d.config.toSortedMap().map { (k, v) -> "$k=$v" })

    fun hashOf(p: ProfileRecord): String =
        sha1(listOf(p.name, p.sex, p.birthDate, num(p.heightCm), p.targetWeightKg?.let(::num) ?: "-"))

    fun hashOf(s: SettingsRecord): String =
        sha1(s.values.toSortedMap().map { (k, v) -> "$k=${if (v is Number) num(v) else v.toString()}" })

    // ---- Base trust ----------------------------------------------------------------------------

    /**
     * The base can be used to detect deletions on the phone only if it describes the same local
     * dataset and the phone did not lose everything (fresh install, wiped database).
     */
    fun isBaseTrusted(base: SyncBase?, local: LocalSnapshot): Boolean {
        if (base == null || base.datasetKey != local.datasetKey) return false
        val phoneLostMeasurements = local.measurements.isEmpty() && base.measurements.values.any { it is BaseEntry.Live }
        val phoneLostDevices = local.devices.isEmpty() && base.devices.values.any { it is BaseEntry.Live }
        return !phoneLostMeasurements && !phoneLostDevices
    }

    /** An untrusted base only keeps what it knows about cloud tombstones. */
    private fun usableEntries(base: Map<String, BaseEntry>?, trusted: Boolean): Map<String, BaseEntry> = when {
        base == null -> emptyMap()
        trusted -> base
        else -> base.filterValues { it is BaseEntry.Deleted }
    }

    // ---- Plan ----------------------------------------------------------------------------------

    fun plan(local: LocalSnapshot, remote: RemoteSnapshot, base: SyncBase?, now: Long): Plan {
        val trusted = isBaseTrusted(base, local)
        val uploads = ArrayList<CloudWrite>()

        var profileToApply: ProfileRecord? = null
        when (decide(local.profile, remote.profile, ::hashOf) { it.editedAt }) {
            Side.UPLOAD -> uploads += CloudWrite.PutProfile(local.profile!!)
            Side.DOWNLOAD -> profileToApply = remote.profile
            Side.NONE -> Unit
        }
        var settingsToApply: SettingsRecord? = null
        when (decide(local.settings, remote.settings, ::hashOf) { it.editedAt }) {
            Side.UPLOAD -> uploads += CloudWrite.PutSettings(local.settings!!)
            Side.DOWNLOAD -> settingsToApply = remote.settings
            Side.NONE -> Unit
        }

        val m = mergeCollection(
            local = local.measurements,
            remoteLive = remote.measurements,
            remoteDead = remote.measurementTombstones,
            base = usableEntries(base?.measurements, trusted),
            now = now,
            id = { it.id },
            hash = ::hashOf,
            updatedAt = { it.updatedAt },
            combine = ::combineMeasurements,
        )
        val d = mergeCollection(
            local = local.devices,
            remoteLive = remote.devices,
            remoteDead = remote.deviceTombstones,
            base = usableEntries(base?.devices, trusted),
            now = now,
            id = { it.id },
            hash = ::hashOf,
            updatedAt = { it.updatedAt },
            combine = { l, r -> if (l.updatedAt > r.updatedAt) l else r },
        )
        m.puts.mapTo(uploads) { CloudWrite.PutMeasurement(it) }
        m.tombstones.mapTo(uploads) { CloudWrite.DeleteMeasurement(it) }
        d.puts.mapTo(uploads) { CloudWrite.PutDevice(it) }
        d.tombstones.mapTo(uploads) { CloudWrite.DeleteDevice(it) }

        return Plan(
            localChanges = RemoteChanges(
                profile = profileToApply,
                settings = settingsToApply,
                upsertMeasurements = m.upserts,
                deleteMeasurementIds = m.localDeletes,
                upsertDevices = d.upserts,
                deleteDeviceIds = d.localDeletes,
            ),
            uploads = uploads,
            measurementTargets = m.targets,
            deviceTargets = d.targets,
        )
    }

    /** Both sides changed the same measurement: keep a known impedance, newest one if both differ. */
    fun combineMeasurements(local: MeasurementRecord, remote: MeasurementRecord): MeasurementRecord {
        val impedance = when {
            local.impedance == null -> remote.impedance
            remote.impedance == null -> local.impedance
            local.updatedAt > remote.updatedAt -> local.impedance
            else -> remote.impedance
        }
        return remote.copy(
            impedance = impedance,
            scaleKey = remote.scaleKey ?: local.scaleKey,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        )
    }

    // ---- Next base -----------------------------------------------------------------------------

    /**
     * The base to store after the plan was applied on the phone ([localAfter]) and uploaded.
     * Built from the pre-sync [local] snapshot, so anything changed on the phone during the sync is
     * still seen as a change next time.
     */
    fun nextBase(
        previous: SyncBase?,
        local: LocalSnapshot,
        remote: RemoteSnapshot,
        plan: Plan,
        localAfter: LocalSnapshot,
        now: Long,
    ): SyncBase {
        val measurements = nextEntries(
            previous = previous?.measurements,
            remoteDead = remote.measurementTombstones,
            uploadedDead = plan.uploads.filterIsInstance<CloudWrite.DeleteMeasurement>().map { it.tombstone },
            targets = plan.measurementTargets,
            applied = plan.localChanges.upsertMeasurements.mapTo(HashSet()) { it.id },
            localIds = local.measurements.mapTo(HashSet()) { it.id },
            after = localAfter.measurements.associateBy { it.id },
            hash = ::hashOf,
        )
        val devices = nextEntries(
            previous = previous?.devices,
            remoteDead = remote.deviceTombstones,
            uploadedDead = plan.uploads.filterIsInstance<CloudWrite.DeleteDevice>().map { it.tombstone },
            targets = plan.deviceTargets,
            applied = plan.localChanges.upsertDevices.mapTo(HashSet()) { it.id },
            localIds = local.devices.mapTo(HashSet()) { it.id },
            after = localAfter.devices.associateBy { it.id },
            hash = ::hashOf,
        )
        val trustedCursor = if (isBaseTrusted(previous, local)) previous?.lastPullAt else null
        val cursor = if (remote.complete) remote.maxUpdatedAt else maxOfNullable(trustedCursor, remote.maxUpdatedAt)
        return SyncBase(
            datasetKey = local.datasetKey,
            lastPullAt = cursor,
            lastSyncAt = now,
            measurements = measurements,
            devices = devices,
        )
    }

    // ---- Internals -----------------------------------------------------------------------------

    private enum class Side { NONE, UPLOAD, DOWNLOAD }

    private fun <T : Any> decide(local: T?, remote: T?, hash: (T) -> String, editedAt: (T) -> Long): Side = when {
        local == null && remote == null -> Side.NONE
        remote == null -> Side.UPLOAD
        local == null -> Side.DOWNLOAD
        hash(local) == hash(remote) -> Side.NONE
        editedAt(local) > editedAt(remote) -> Side.UPLOAD
        else -> Side.DOWNLOAD
    }

    private class CollectionMerge<T>(
        val upserts: List<T>,
        val localDeletes: Set<String>,
        val puts: List<T>,
        val tombstones: List<Tombstone>,
        val targets: Map<String, T>,
    )

    private fun <T> mergeCollection(
        local: List<T>,
        remoteLive: List<T>,
        remoteDead: List<Tombstone>,
        base: Map<String, BaseEntry>,
        now: Long,
        id: (T) -> String,
        hash: (T) -> String,
        updatedAt: (T) -> Long,
        combine: (T, T) -> T,
    ): CollectionMerge<T> {
        val localById = LinkedHashMap<String, T>()
        for (item in local) localById.putIfAbsent(id(item), item)
        val remoteById = LinkedHashMap<String, T>()
        for (item in remoteLive) remoteById.putIfAbsent(id(item), item)
        val deadById = LinkedHashMap<String, Tombstone>()
        for (t in remoteDead) deadById.putIfAbsent(t.id, t)

        val ids = LinkedHashSet<String>().apply {
            addAll(localById.keys)
            addAll(remoteById.keys)
            addAll(deadById.keys)
            addAll(base.keys)
        }

        val upserts = ArrayList<T>()
        val localDeletes = LinkedHashSet<String>()
        val puts = ArrayList<T>()
        val tombstones = ArrayList<Tombstone>()
        val targets = LinkedHashMap<String, T>()

        for (key in ids) {
            val l = localById[key]
            val r = remoteById[key]
            val dead = deadById[key]
            val b = base[key]
            when {
                l != null && dead != null -> {
                    if (updatedAt(l) > dead.deletedAt) {
                        puts += l
                        targets[key] = l
                    } else {
                        localDeletes += key
                    }
                }
                l != null && r != null -> {
                    val hl = hash(l)
                    val hr = hash(r)
                    if (hl == hr) {
                        targets[key] = l
                    } else {
                        val agreed = (b as? BaseEntry.Live)?.hash
                        val merged = when (agreed) {
                            hl -> r // only the cloud changed
                            hr -> l // only the phone changed
                            else -> combine(l, r)
                        }
                        val hm = hash(merged)
                        if (hm != hr) puts += merged
                        if (hm != hl) upserts += merged
                        targets[key] = merged
                    }
                }
                l != null -> when (b) {
                    null -> {
                        puts += l
                        targets[key] = l
                    }
                    is BaseEntry.Live -> {
                        if (b.hash != hash(l)) puts += l
                        targets[key] = l
                    }
                    is BaseEntry.Deleted -> {
                        if (updatedAt(l) > b.deletedAt) {
                            puts += l
                            targets[key] = l
                        } else {
                            localDeletes += key
                        }
                    }
                }
                r != null -> if (b is BaseEntry.Live) {
                    tombstones += Tombstone(key, now) // deleted on the phone since the last sync
                } else {
                    upserts += r
                    targets[key] = r
                }
                dead != null -> Unit
                b is BaseEntry.Live -> tombstones += Tombstone(key, now)
            }
        }
        return CollectionMerge(upserts, localDeletes, puts, tombstones, targets)
    }

    private fun <T> nextEntries(
        previous: Map<String, BaseEntry>?,
        remoteDead: List<Tombstone>,
        uploadedDead: List<Tombstone>,
        targets: Map<String, T>,
        applied: Set<String>,
        localIds: Set<String>,
        after: Map<String, T>,
        hash: (T) -> String,
    ): Map<String, BaseEntry> {
        val out = HashMap<String, BaseEntry>()
        previous?.forEach { (key, entry) -> if (entry is BaseEntry.Deleted) out[key] = entry }
        for (t in remoteDead) out[t.id] = BaseEntry.Deleted(t.deletedAt)
        for (t in uploadedDead) out[t.id] = BaseEntry.Deleted(t.deletedAt)
        for ((key, target) in targets) {
            val current = after[key]
            // Inserted from the cloud but not stored on the phone: unknown, retried on a full pull.
            if (key !in localIds && current == null) continue
            // The phone could not take the agreed version: remember what it has, so it is not re-uploaded.
            val h = if (key in applied && current != null && hash(current) != hash(target)) hash(current) else hash(target)
            out[key] = BaseEntry.Live(h)
        }
        return out
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun num(n: Number): String = n.toDouble().toString()

    private fun sha1(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        for (p in parts) {
            digest.update(p.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
