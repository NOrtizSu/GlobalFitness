package com.enjoyyourtime.globalfitness.cloud

import java.security.MessageDigest

/**
 * Pure merge of the phone's data with the cloud copy of the SAME dataset (see [DatasetGate]),
 * plus the two "replace" plans used when the datasets differ. No Android, no Firebase; unit-tested.
 *
 * - Profile and settings: last writer wins by edit time (ties go to the cloud copy). A profile
 *   deleted on this phone ([LocalSnapshot.profileDeletedAt]) becomes a profile tombstone.
 * - Measurements and devices: union by id. A three-way compare against the last agreed [SyncBase]
 *   tells which side changed; when both changed, measurements keep the known impedance.
 * - Deletions are tombstones. A tombstone wins over a live version that is not newer than it, and
 *   always over a local copy that is unchanged since the last sync.
 * - Something that disappeared from the phone since the last sync becomes a tombstone when the
 *   base belongs to the same dataset (same persistent dataset id), even the last measurement.
 * - Cloud copies of the same weighing taken by another phone (within [DuplicateRule]) are linked
 *   to the local measurement as aliases: never inserted twice, deleted together.
 */
object SyncMerge {
    /** A sync deleting at least this many measurements, and half of the synced ones, asks first. */
    const val MASS_DELETE_MIN = 10

    enum class Kind { MERGE, ADOPT_CLOUD, REPLACE_CLOUD }

    class Plan internal constructor(
        val kind: Kind,
        /** Apply these on the phone. */
        val localChanges: RemoteChanges,
        /** Write these to the cloud. */
        val uploads: List<CloudWrite>,
        internal val measurementTargets: Map<String, MeasurementRecord>,
        internal val deviceTargets: Map<String, DeviceRecord>,
        /** Cloud id -> local id of the same weighing. */
        internal val aliases: Map<String, String>,
    ) {
        val isNoOp: Boolean get() = localChanges.isEmpty && uploads.isEmpty()

        val measurementDeletions: Int get() = uploads.count { it is CloudWrite.DeleteMeasurement }
    }

    // ---- Content hashes (what counts as "changed") ---------------------------------------------

    /**
     * Only the mutable fields (scale data and origin): the id already encodes timestamp and weight.
     * Fields added later only count when present, so the hash of older records never changes and
     * records that gain them (e.g. skeletal muscle, low impedance) are uploaded again.
     */
    fun hashOf(m: MeasurementRecord): String {
        val base = "${m.impedance ?: "-"}|${m.source}"
        if (m.composition.isEmpty() && m.compositionSource == null && m.formulaLib == null && m.deviceDriver == null && m.impedanceLow == null) {
            return base
        }
        val composition = m.composition.toSortedMap().entries.joinToString(",") { (k, v) -> "$k=${num(v)}" }
        val low = m.impedanceLow?.let { "|low=$it" }.orEmpty()
        return "$base|$composition|${m.compositionSource ?: "-"}|${m.formulaLib ?: "-"}|${m.deviceDriver ?: "-"}$low"
    }

    fun hashOf(d: DeviceRecord): String =
        sha1(listOf(d.driverId, d.name) + d.config.toSortedMap().map { (k, v) -> "$k=$v" })

    fun hashOf(p: ProfileRecord): String =
        sha1(listOf(p.name, p.sex, p.birthDate, num(p.heightCm), p.targetWeightKg?.let(::num) ?: "-"))

    fun hashOf(s: SettingsRecord): String =
        sha1(s.values.toSortedMap().map { (k, v) -> "$k=${if (v is Number) num(v) else v.toString()}" })

    // ---- Base trust ----------------------------------------------------------------------------

    /** The base detects deletions on the phone only if it was made for this very dataset. */
    fun isBaseTrusted(base: SyncBase?, local: LocalSnapshot): Boolean =
        base != null && local.datasetId.isNotEmpty() && base.datasetKey == local.datasetId

    /** A merge that would delete many synced measurements at once (likely a reset or a bug). */
    fun isMassDeletion(plan: Plan, base: SyncBase?): Boolean {
        if (plan.kind != Kind.MERGE) return false
        val deletions = plan.measurementDeletions
        val synced = base?.measurements?.values?.count { it is BaseEntry.Live } ?: 0
        return deletions >= MASS_DELETE_MIN && deletions * 2 >= synced
    }

    /** The same base without its "synced" entries: a union merge that restores what the phone lost. */
    fun withoutLiveEntries(base: SyncBase): SyncBase = base.copy(
        measurements = base.measurements.filterValues { it is BaseEntry.Deleted },
        devices = base.devices.filterValues { it is BaseEntry.Deleted },
    )

    // ---- Merge plan (same dataset) -------------------------------------------------------------

    fun plan(local: LocalSnapshot, remote: RemoteSnapshot, base: SyncBase?, now: Long): Plan {
        val entries = if (isBaseTrusted(base, local)) base else null
        val uploads = ArrayList<CloudWrite>()
        val profileToApply = planProfile(local, remote, uploads)

        var settingsToApply: SettingsRecord? = null
        val localSettings = local.settings
        val remoteSettings = remote.settings
        when {
            localSettings == null && remoteSettings == null -> Unit
            remoteSettings == null -> uploads += CloudWrite.PutSettings(localSettings!!)
            localSettings == null -> settingsToApply = remoteSettings
            hashOf(localSettings) == hashOf(remoteSettings) -> Unit
            localSettings.editedAt > remoteSettings.editedAt -> uploads += CloudWrite.PutSettings(localSettings)
            else -> settingsToApply = remoteSettings
        }

        val measurementBase = entries?.measurements.orEmpty()
        val aliases = LinkedHashMap<String, String>()
        measurementBase.forEach { (k, e) -> if (e is BaseEntry.Alias) aliases[k] = e.localId }
        local.duplicateRule?.let { aliases += findAliases(local.measurements, remote.measurements, measurementBase, it) }

        val m = mergeCollection(
            local = local.measurements,
            remoteLive = remote.measurements,
            remoteDead = remote.measurementTombstones,
            base = measurementBase,
            aliases = aliases,
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
            base = entries?.devices.orEmpty(),
            aliases = emptyMap(),
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
            kind = Kind.MERGE,
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
            aliases = m.aliases,
        )
    }

    /**
     * Both sides changed the same measurement: keep the version with more scale data (impedance,
     * composition), the newest one if both have the same amount. Scale data stays one unit.
     */
    fun combineMeasurements(local: MeasurementRecord, remote: MeasurementRecord): MeasurementRecord {
        fun richness(m: MeasurementRecord) = (if (m.impedance != null) 1 else 0) + (if (m.composition.isNotEmpty()) 1 else 0)
        val winner = when {
            richness(local) > richness(remote) -> local
            richness(remote) > richness(local) -> remote
            local.updatedAt > remote.updatedAt -> local
            else -> remote
        }
        return winner.copy(
            source = remote.source,
            scaleKey = remote.scaleKey ?: local.scaleKey,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        )
    }

    // ---- Replace plans (different datasets, after the user's choice or on a fresh phone) --------

    /** The cloud copy replaces the phone's data. [remote] must be a complete read. */
    fun planAdoptCloud(local: LocalSnapshot, remote: RemoteSnapshot): Plan {
        val remoteById = LinkedHashMap<String, MeasurementRecord>()
        for (r in remote.measurements) remoteById.putIfAbsent(r.id, r)
        val localById = local.measurements.associateBy { it.id }
        val remoteDevices = remote.devices.associateBy { it.id }
        val localDevices = local.devices.associateBy { it.id }
        val remoteProfile = remote.profile
        return Plan(
            kind = Kind.ADOPT_CLOUD,
            localChanges = RemoteChanges(
                profile = remoteProfile?.takeIf { r -> local.profile?.let { hashOf(it) } != hashOf(r) },
                settings = remote.settings?.takeIf { r -> local.settings?.let { hashOf(it) } != hashOf(r) },
                upsertMeasurements = remoteById.values.filter { r -> localById[r.id]?.let { hashOf(it) } != hashOf(r) },
                deleteMeasurementIds = localById.keys - remoteById.keys,
                upsertDevices = remoteDevices.values.filter { r -> localDevices[r.id]?.let { hashOf(it) } != hashOf(r) },
                deleteDeviceIds = localDevices.keys - remoteDevices.keys,
            ),
            // A cloud copy written before dataset ids existed gets ours stamped on it.
            uploads = if ((remote.profileDoc as? RemoteProfileDoc.Live)?.datasetId == null && remoteProfile != null) {
                listOf(CloudWrite.PutProfile(remoteProfile, local.datasetId))
            } else {
                emptyList()
            },
            measurementTargets = remoteById,
            deviceTargets = remoteDevices,
            aliases = emptyMap(),
        )
    }

    /** The phone's data replaces the cloud copy. [remote] must be a complete read. */
    fun planReplaceCloud(local: LocalSnapshot, remote: RemoteSnapshot, now: Long): Plan {
        val localById = LinkedHashMap<String, MeasurementRecord>()
        for (m in local.measurements) localById.putIfAbsent(m.id, m)
        val localDevices = LinkedHashMap<String, DeviceRecord>()
        for (d in local.devices) localDevices.putIfAbsent(d.id, d)
        val uploads = ArrayList<CloudWrite>()
        val profile = local.profile
        if (profile != null) {
            uploads += CloudWrite.PutProfile(profile, local.datasetId)
        } else if (remote.profileDoc is RemoteProfileDoc.Live) {
            uploads += CloudWrite.DeleteProfile(now, local.datasetId)
        }
        local.settings?.let { uploads += CloudWrite.PutSettings(it) }
        localById.values.mapTo(uploads) { CloudWrite.PutMeasurement(it, force = true) }
        remote.measurements.map { it.id }.distinct().filter { it !in localById }
            .mapTo(uploads) { CloudWrite.DeleteMeasurement(Tombstone(it, now)) }
        localDevices.values.mapTo(uploads) { CloudWrite.PutDevice(it) }
        remote.devices.map { it.id }.distinct().filter { it !in localDevices }
            .mapTo(uploads) { CloudWrite.DeleteDevice(Tombstone(it, now)) }
        return Plan(
            kind = Kind.REPLACE_CLOUD,
            localChanges = RemoteChanges(),
            uploads = uploads,
            measurementTargets = localById,
            deviceTargets = localDevices,
            aliases = emptyMap(),
        )
    }

    /**
     * Drops profile / settings changes when the user edited them after [planned] was taken, so a
     * download never overwrites an edit made during the sync (the next sync decides again).
     */
    fun dropStaleSingles(changes: RemoteChanges, planned: LocalSnapshot, current: LocalSnapshot): RemoteChanges =
        changes.copy(
            profile = changes.profile?.takeIf { planned.profile?.let(::hashOf) == current.profile?.let(::hashOf) },
            settings = changes.settings?.takeIf { planned.settings?.let(::hashOf) == current.settings?.let(::hashOf) },
        )

    // ---- Next base -----------------------------------------------------------------------------

    /**
     * The base to store after the plan was applied on the phone ([localAfter], taken right after
     * the local apply) and uploaded. Built from the pre-sync [local] snapshot, so anything changed
     * on the phone during the sync is still seen as a change next time.
     */
    fun nextBase(
        previous: SyncBase?,
        local: LocalSnapshot,
        remote: RemoteSnapshot,
        plan: Plan,
        localAfter: LocalSnapshot,
        now: Long,
    ): SyncBase {
        val trusted = plan.kind == Kind.MERGE && isBaseTrusted(previous, local)
        val measurements = nextEntries(
            previous = previous?.measurements.takeIf { trusted },
            remoteDead = if (plan.kind == Kind.REPLACE_CLOUD) emptyList() else remote.measurementTombstones,
            uploadedDead = plan.uploads.filterIsInstance<CloudWrite.DeleteMeasurement>().map { it.tombstone },
            targets = plan.measurementTargets,
            applied = plan.localChanges.upsertMeasurements.mapTo(HashSet()) { it.id },
            localIds = local.measurements.mapTo(HashSet()) { it.id },
            after = localAfter.measurements.associateBy { it.id },
            hash = ::hashOf,
        ).toMutableMap()
        for ((cloudId, localId) in plan.aliases) {
            if (measurements[cloudId] == null) measurements[cloudId] = BaseEntry.Alias(localId)
        }
        val devices = nextEntries(
            previous = previous?.devices.takeIf { trusted },
            remoteDead = if (plan.kind == Kind.REPLACE_CLOUD) emptyList() else remote.deviceTombstones,
            uploadedDead = plan.uploads.filterIsInstance<CloudWrite.DeleteDevice>().map { it.tombstone },
            targets = plan.deviceTargets,
            applied = plan.localChanges.upsertDevices.mapTo(HashSet()) { it.id },
            localIds = local.devices.mapTo(HashSet()) { it.id },
            after = localAfter.devices.associateBy { it.id },
            hash = ::hashOf,
        )
        val cursor = if (remote.complete) {
            remote.maxUpdatedAt
        } else {
            maxOfNullable(previous?.lastPullAt.takeIf { trusted }, remote.maxUpdatedAt)
        }
        return SyncBase(
            datasetKey = local.datasetId,
            lastPullAt = cursor,
            lastSyncAt = now,
            measurements = measurements,
            devices = devices,
            profileSynced = localAfter.profile != null || (trusted && previous?.profileSynced == true),
            premiumMirrored = trusted && previous?.premiumMirrored == true,
        )
    }

    // ---- Internals -----------------------------------------------------------------------------

    /** Profile: last writer wins; a profile deleted here becomes a tombstone. Returns what to apply. */
    private fun planProfile(local: LocalSnapshot, remote: RemoteSnapshot, uploads: MutableList<CloudWrite>): ProfileRecord? {
        val deletedAt = local.profileDeletedAt
        // A profile created after deleting the previous one is at least as new as that deletion.
        val mine = local.profile?.let { p -> if (deletedAt != null && deletedAt > p.editedAt) p.copy(editedAt = deletedAt) else p }
        when (val doc = remote.profileDoc) {
            RemoteProfileDoc.Absent -> if (mine != null) uploads += CloudWrite.PutProfile(mine, local.datasetId)
            is RemoteProfileDoc.Deleted ->
                if (mine != null && mine.editedAt > doc.deletedAt) uploads += CloudWrite.PutProfile(mine, local.datasetId)
            is RemoteProfileDoc.Live -> {
                val theirs = doc.record
                when {
                    mine == null -> when {
                        deletedAt != null && (theirs == null || theirs.editedAt <= deletedAt) ->
                            uploads += CloudWrite.DeleteProfile(deletedAt, local.datasetId)
                        theirs != null -> return theirs // edited elsewhere after the deletion here
                    }
                    theirs == null -> uploads += CloudWrite.PutProfile(mine, local.datasetId)
                    hashOf(mine) == hashOf(theirs) -> Unit
                    mine.editedAt > theirs.editedAt -> uploads += CloudWrite.PutProfile(mine, local.datasetId)
                    else -> return theirs
                }
            }
        }
        return null
    }

    /**
     * Cloud measurements this phone has never seen that are the same weighing as a local one with a
     * different id (two phones heard the same scale a few seconds apart): cloud id -> local id.
     */
    private fun findAliases(
        local: List<MeasurementRecord>,
        remote: List<MeasurementRecord>,
        base: Map<String, BaseEntry>,
        rule: DuplicateRule,
    ): Map<String, String> {
        if (local.isEmpty() || remote.isEmpty()) return emptyMap()
        val localIds = local.mapTo(HashSet()) { it.id }
        val sorted = local.sortedBy { it.timestamp }
        val times = LongArray(sorted.size) { sorted[it].timestamp }
        val out = LinkedHashMap<String, String>()
        for (r in remote) {
            if (r.id in localIds || base[r.id] != null) continue
            var i = lowerBound(times, r.timestamp - rule.windowMs)
            while (i < sorted.size && sorted[i].timestamp <= r.timestamp + rule.windowMs) {
                if (rule.sameWeighing(sorted[i], r)) {
                    out[r.id] = sorted[i].id
                    break
                }
                i++
            }
        }
        return out
    }

    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private class CollectionMerge<T>(
        val upserts: List<T>,
        val localDeletes: Set<String>,
        val puts: List<T>,
        val tombstones: List<Tombstone>,
        val targets: Map<String, T>,
        val aliases: Map<String, String>,
    )

    private fun <T> mergeCollection(
        local: List<T>,
        remoteLive: List<T>,
        remoteDead: List<Tombstone>,
        base: Map<String, BaseEntry>,
        aliases: Map<String, String>,
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

        val upserts = ArrayList<T>()
        val localDeletes = LinkedHashSet<String>()
        val puts = ArrayList<T>()
        val tombstones = ArrayList<Tombstone>()
        val targets = LinkedHashMap<String, T>()
        val tombstoned = HashSet<String>()

        fun unchangedSinceSync(key: String, item: T): Boolean {
            val b = base[key]
            return b is BaseEntry.Live && b.hash == hash(item)
        }

        fun tombstone(key: String, at: Long) {
            if (key !in deadById && tombstoned.add(key)) tombstones += Tombstone(key, at)
        }

        val aliasIds = aliases.keys - localById.keys

        // Another phone deleted its copy of a weighing that is also on this phone.
        for (aliasId in aliasIds) {
            val dead = deadById[aliasId] ?: continue
            val target = aliases.getValue(aliasId)
            val item = localById[target] ?: continue
            if (unchangedSinceSync(target, item) || updatedAt(item) <= dead.deletedAt) {
                localDeletes += target
                if (base[target] is BaseEntry.Live) tombstone(target, dead.deletedAt)
            }
        }

        val ids = LinkedHashSet<String>().apply {
            addAll(localById.keys)
            addAll(remoteById.keys)
            addAll(deadById.keys)
            addAll(base.keys)
            removeAll(aliasIds)
        }
        for (key in ids) {
            if (key in localDeletes) continue
            val l = localById[key]
            val r = remoteById[key]
            val dead = deadById[key]
            val b = base[key]
            when {
                l != null && dead != null -> {
                    if (!unchangedSinceSync(key, l) && updatedAt(l) > dead.deletedAt) {
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
                    else -> {
                        puts += l
                        targets[key] = l
                    }
                }
                r != null -> if (b is BaseEntry.Live) {
                    tombstone(key, now) // deleted on the phone since the last sync
                } else {
                    upserts += r
                    targets[key] = r
                }
                dead != null -> Unit
                b is BaseEntry.Live -> tombstone(key, now)
            }
        }

        // Deleting a measurement here deletes the other phones' copies of the same weighing too.
        val keptAliases = LinkedHashMap<String, String>()
        for (aliasId in aliasIds) {
            val target = aliases.getValue(aliasId)
            if (aliasId in deadById) continue
            if (localById[target] == null || target in localDeletes || target in tombstoned) {
                tombstone(aliasId, now)
            } else {
                keptAliases[aliasId] = target
            }
        }
        return CollectionMerge(upserts, localDeletes, puts, tombstones, targets, keptAliases)
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
