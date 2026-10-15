package com.enjoyyourtime.globalfitness.cloud

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.enjoyyourtime.globalfitness.account.AccountManager
import com.enjoyyourtime.globalfitness.account.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the account screen shows about the backup. */
data class CloudSyncStatus(
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    /** Wall-clock time of the last successful sync. */
    val lastSyncAt: Long? = null,
    /** Spanish error of the last attempt, null when it succeeded. */
    val error: String? = null,
    /** A retry with backoff is scheduled. */
    val retrying: Boolean = false,
    /** Some changes are saved in the offline queue and will be sent when there is connection. */
    val pendingUpload: Boolean = false,
)

/**
 * Keeps the local data and the user's cloud copy in sync while signed in:
 * a merge right after sign-in and on app start, a pull/merge when the app comes back to the
 * foreground or on "Sincronizar ahora", and a debounced upload after local changes.
 * Everything runs in the background with retries; errors are surfaced in [status], never thrown.
 */
@OptIn(FlowPreview::class)
class CloudSyncEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val account: AccountManager,
    private val source: BackupDataSource,
    isPremium: StateFlow<Boolean>,
    private val remote: FirestoreBackupRemote = FirestoreBackupRemote(),
    private val store: SyncStateStore = SyncStateStore(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var stamps: EditStamps? = null
    private var lastForegroundSync = 0L

    @Volatile
    private var deleting = false

    private val _status = MutableStateFlow(CloudSyncStatus(signedIn = account.user.value != null))
    val status: StateFlow<CloudSyncStatus> = _status.asStateFlow()

    init {
        scope.launch { watchLocalChanges() }
        scope.launch { runWhileSignedIn() }
        scope.launch {
            val mirrored = HashSet<String>()
            combine(account.user.map { it?.uid }, isPremium) { uid, premium -> uid.takeIf { premium } }
                .distinctUntilChanged()
                .collect { uid ->
                    if (uid != null && uid !in mirrored) {
                        try {
                            remote.markPremium(uid)
                            mirrored += uid
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not mirror premium flag", e)
                        }
                    }
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) onAppForeground() },
        )
    }

    /** "Sincronizar ahora". */
    fun syncNow() {
        requests.trySend(Unit)
    }

    /**
     * Deletes the whole cloud backup and the Firebase account (Play policy: account deletion).
     * Re-authenticates first, so nothing is deleted if the user backs out. Local data stays.
     */
    suspend fun deleteCloudDataAndAccount(activity: Activity): AuthResult {
        val uid = account.currentUid() ?: return AuthResult.Failure("No hay ninguna sesión iniciada.")
        deleting = true
        try {
            return mutex.withLock {
                val reauth = account.reauthenticate(activity)
                if (reauth != AuthResult.Success) return@withLock reauth
                // Forget what was synced: if anything below fails, the next sync re-uploads from scratch.
                store.clearBase(uid)
                try {
                    remote.deleteAll(uid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud deletion failed", e)
                    return@withLock AuthResult.Failure("No se pudieron borrar los datos de la nube. ${CloudErrors.message(e)}")
                }
                account.deleteUser()
            }
        } finally {
            deleting = false
        }
    }

    private fun onAppForeground() {
        val now = SystemClock.elapsedRealtime()
        if (account.user.value == null || now - lastForegroundSync < FOREGROUND_SYNC_MIN_INTERVAL_MS) return
        lastForegroundSync = now
        requests.trySend(Unit)
    }

    /** Records edit times of local changes and schedules a debounced upload while signed in. */
    private suspend fun watchLocalChanges() {
        source.changes.debounce(LOCAL_CHANGE_DEBOUNCE_MS).collect {
            try {
                mutex.withLock { stamped(source.snapshot()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not record local edit time", e)
            }
            if (account.user.value != null) requests.trySend(Unit)
        }
    }

    private suspend fun runWhileSignedIn() {
        account.user.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
            if (uid == null) {
                _status.value = CloudSyncStatus()
                return@collectLatest
            }
            val base = store.loadBase(uid)
            _status.value = CloudSyncStatus(signedIn = true, lastSyncAt = base?.lastSyncAt)
            lastForegroundSync = SystemClock.elapsedRealtime()
            requests.trySend(Unit) // first merge after sign-in / app start
            for (request in requests) syncWithRetry(uid)
        }
    }

    private suspend fun syncWithRetry(uid: String) {
        var failures = 0
        while (!deleting) {
            _status.update { it.copy(syncing = true) }
            try {
                val queued = syncOnce(uid)
                if (queued == null) {
                    _status.update { it.copy(syncing = false) }
                    return
                }
                _status.update {
                    it.copy(syncing = false, retrying = false, error = null, lastSyncAt = clock(), pendingUpload = queued)
                }
                return
            } catch (e: CancellationException) {
                _status.update { it.copy(syncing = false) }
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Sync failed (attempt $failures)", e)
                val again = CloudErrors.isRetryable(e) && retry.canRetry(failures)
                _status.update { it.copy(syncing = false, retrying = again, error = CloudErrors.message(e)) }
                if (!again) return
                delay(retry.delayFor(failures))
            }
        }
    }

    /** One full sync. Returns whether writes are still queued offline, or null if it was skipped. */
    private suspend fun syncOnce(uid: String): Boolean? = mutex.withLock {
        if (deleting || account.currentUid() != uid) return@withLock null
        val local = stamped(source.snapshot())
        val base = store.loadBase(uid)
        val since = if (SyncMerge.isBaseTrusted(base, local)) base?.lastPullAt else null
        val remoteSnapshot = remote.fetch(uid, since)
        val now = clock()
        val plan = withContext(Dispatchers.Default) { SyncMerge.plan(local, remoteSnapshot, base, now) }

        if (!plan.localChanges.isEmpty) source.apply(plan.localChanges)
        val confirmed = if (plan.uploads.isEmpty()) true else remote.write(uid, plan.uploads)
        val after = source.snapshot()
        rememberAppliedEdits(plan.localChanges, after)
        store.saveBase(uid, SyncMerge.nextBase(base, local, remoteSnapshot, plan, after, now))
        !confirmed
    }

    /** Fills profile / settings edit times from the stamps (see [EditStamp.observe]). Call under [mutex]. */
    private suspend fun stamped(local: LocalSnapshot): LocalSnapshot {
        val current = stamps ?: store.loadStamps()
        val now = clock()
        val profileStamp = local.profile?.let { EditStamp.observe(current.profile, SyncMerge.hashOf(it), now, it.editedAt) }
        val settingsStamp = local.settings?.let { EditStamp.observe(current.settings, SyncMerge.hashOf(it), now, it.editedAt) }
        val updated = EditStamps(profileStamp ?: current.profile, settingsStamp ?: current.settings)
        stamps = updated
        if (updated != current) store.saveStamps(updated)
        return local.copy(
            profile = local.profile?.let { p -> p.copy(editedAt = maxOf(p.editedAt, profileStamp?.at ?: 0L)) },
            settings = local.settings?.let { s -> s.copy(editedAt = maxOf(s.editedAt, settingsStamp?.at ?: 0L)) },
        )
    }

    /** Content that came from the cloud keeps the cloud's edit time instead of looking like a new local edit. */
    private suspend fun rememberAppliedEdits(applied: RemoteChanges, after: LocalSnapshot) {
        val current = stamps ?: store.loadStamps()
        val profile = applied.profile?.let { remoteProfile ->
            after.profile?.let { EditStamp(SyncMerge.hashOf(it), remoteProfile.editedAt) }
        }
        val settings = applied.settings?.let { remoteSettings ->
            after.settings?.let { EditStamp(SyncMerge.hashOf(it), remoteSettings.editedAt) }
        }
        if (profile == null && settings == null) return
        val updated = EditStamps(profile ?: current.profile, settings ?: current.settings)
        stamps = updated
        store.saveStamps(updated)
    }

    private companion object {
        const val TAG = "CloudSync"
        const val LOCAL_CHANGE_DEBOUNCE_MS = 3_000L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 60_000L
    }
}
