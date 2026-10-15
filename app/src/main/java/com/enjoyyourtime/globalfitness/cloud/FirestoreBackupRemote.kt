package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.DEVICES
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_PREMIUM
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_SCHEMA
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_UPDATED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.MEASUREMENTS
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.META
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.PROFILE_DOC
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.SETTINGS_DOC
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.USERS
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/** Thrown when the server did not confirm a destructive operation in time. */
class CloudTimeoutException : Exception("timeout")

/**
 * Firestore access for the backup tree `users/{uid}/...` (see [FirestoreCodec]). Reads always go to
 * the server, so a sync never merges against a stale offline cache. Writes use the persistent
 * offline cache: if the confirmation does not arrive in time they stay queued and Firestore sends
 * them when the connection comes back.
 */
class FirestoreBackupRemote {
    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { firestore ->
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }

    private fun user(uid: String) = db.collection(USERS).document(uid)
    private fun profileRef(uid: String) = user(uid).collection(META).document(PROFILE_DOC)
    private fun settingsRef(uid: String) = user(uid).collection(META).document(SETTINGS_DOC)

    /** Reads the profile, settings, all devices and the measurements changed since [since] (all if null). */
    suspend fun fetch(uid: String, since: Long?): RemoteSnapshot = coroutineScope {
        val profile = async { profileRef(uid).get(Source.SERVER).await() }
        val settings = async { settingsRef(uid).get(Source.SERVER).await() }
        val devices = async { user(uid).collection(DEVICES).get(Source.SERVER).await() }
        val measurementsQuery: Query = if (since == null) {
            user(uid).collection(MEASUREMENTS)
        } else {
            user(uid).collection(MEASUREMENTS).whereGreaterThan(F_UPDATED_AT, Timestamp(Date(since - PULL_OVERLAP_MS)))
        }
        val measurements = async { measurementsQuery.get(Source.SERVER).await() }

        val liveMeasurements = ArrayList<MeasurementRecord>()
        val deadMeasurements = ArrayList<Tombstone>()
        var maxUpdatedAt: Long? = null
        for (doc in measurements.await().documents) {
            val updatedAt = doc.updatedAtMillis()
            if (updatedAt != null) maxUpdatedAt = maxOf(maxUpdatedAt ?: updatedAt, updatedAt)
            when (val decoded = FirestoreCodec.measurementFromMap(doc.id, doc.data.orEmpty(), updatedAt)) {
                is Decoded.Live -> liveMeasurements += decoded.record
                is Decoded.Dead -> deadMeasurements += decoded.tombstone
                null -> Unit
            }
        }
        val liveDevices = ArrayList<DeviceRecord>()
        val deadDevices = ArrayList<Tombstone>()
        for (doc in devices.await().documents) {
            when (val decoded = FirestoreCodec.deviceFromMap(doc.id, doc.data.orEmpty(), doc.updatedAtMillis())) {
                is Decoded.Live -> liveDevices += decoded.record
                is Decoded.Dead -> deadDevices += decoded.tombstone
                null -> Unit
            }
        }
        val profileDoc = profile.await()
        val settingsDoc = settings.await()
        RemoteSnapshot(
            profile = profileDoc.data?.let { FirestoreCodec.profileFromMap(it, profileDoc.updatedAtMillis()) },
            settings = settingsDoc.data?.let { FirestoreCodec.settingsFromMap(it, settingsDoc.updatedAtMillis()) },
            measurements = liveMeasurements,
            measurementTombstones = deadMeasurements,
            devices = liveDevices,
            deviceTombstones = deadDevices,
            complete = since == null,
            maxUpdatedAt = maxUpdatedAt,
        )
    }

    /**
     * Writes in batches of at most [MAX_BATCH_WRITES]. Returns true when the server confirmed every
     * batch, false when some are still queued in the offline cache.
     */
    suspend fun write(uid: String, writes: List<CloudWrite>): Boolean {
        var allConfirmed = true
        for (chunk in writes.chunked(MAX_BATCH_WRITES)) {
            val batch = db.batch()
            for (w in chunk) {
                when (w) {
                    is CloudWrite.PutProfile ->
                        batch.set(profileRef(uid), FirestoreCodec.profileToMap(w.record).stamped(), SetOptions.merge())
                    is CloudWrite.PutSettings -> {
                        // mergeFields replaces the whole "values" map, so removed keys disappear too.
                        val data = FirestoreCodec.settingsToMap(w.record).stamped()
                        batch.set(settingsRef(uid), data, SetOptions.mergeFields(data.keys.toList()))
                    }
                    is CloudWrite.PutMeasurement -> batch.set(
                        measurementRef(uid, w.record.id),
                        FirestoreCodec.measurementToMap(w.record).stamped() + (F_DELETED_AT to FieldValue.delete()),
                        SetOptions.merge(),
                    )
                    is CloudWrite.DeleteMeasurement ->
                        batch.set(measurementRef(uid, w.tombstone.id), FirestoreCodec.tombstoneToMap(w.tombstone).stamped())
                    is CloudWrite.PutDevice -> {
                        val data = FirestoreCodec.deviceToMap(w.record).stamped() + (F_DELETED_AT to FieldValue.delete())
                        batch.set(deviceRef(uid, w.record.id), data, SetOptions.mergeFields(data.keys.toList()))
                    }
                    is CloudWrite.DeleteDevice ->
                        batch.set(deviceRef(uid, w.tombstone.id), FirestoreCodec.tombstoneToMap(w.tombstone).stamped())
                }
            }
            val confirmed = withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
                batch.commit().await()
                true
            }
            if (confirmed == null) allConfirmed = false
        }
        return allConfirmed
    }

    /** Informational only: Google Play stays the source of truth for the purchase. */
    suspend fun markPremium(uid: String) {
        profileRef(uid).set(
            mapOf(F_PREMIUM to true, "premiumUpdatedAt" to FieldValue.serverTimestamp(), F_SCHEMA to CLOUD_SCHEMA_VERSION),
            SetOptions.merge(),
        ).await()
    }

    /** Deletes the whole backup tree of [uid]. Needs a connection; throws if the server does not confirm. */
    suspend fun deleteAll(uid: String) {
        for (collection in listOf(MEASUREMENTS, DEVICES)) {
            while (true) {
                val page = user(uid).collection(collection).limit(MAX_BATCH_WRITES.toLong()).get(Source.SERVER).await()
                if (page.isEmpty) break
                val batch = db.batch()
                for (doc in page.documents) batch.delete(doc.reference)
                commitConfirmed(batch.commit())
            }
        }
        val batch = db.batch()
        batch.delete(profileRef(uid))
        batch.delete(settingsRef(uid))
        commitConfirmed(batch.commit())
    }

    private suspend fun commitConfirmed(task: com.google.android.gms.tasks.Task<Void>) {
        withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
            task.await()
            true
        } ?: throw CloudTimeoutException()
    }

    private fun measurementRef(uid: String, id: String): DocumentReference = user(uid).collection(MEASUREMENTS).document(id)

    private fun deviceRef(uid: String, id: String): DocumentReference =
        user(uid).collection(DEVICES).document(FirestoreCodec.safeDocId(id))

    private fun Map<String, Any?>.stamped(): Map<String, Any?> = this + (F_UPDATED_AT to FieldValue.serverTimestamp())

    private fun DocumentSnapshot.updatedAtMillis(): Long? = getTimestamp(F_UPDATED_AT)?.toDate()?.time

    companion object {
        /** Firestore allows 500 writes per batch; stay below it. */
        const val MAX_BATCH_WRITES = 400

        /** Incremental pulls re-read this window before the cursor, to absorb clock and commit-order skew. */
        const val PULL_OVERLAP_MS = 10 * 60_000L

        const val COMMIT_TIMEOUT_MS = 30_000L
    }
}
