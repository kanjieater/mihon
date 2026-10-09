package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.PageTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Inject
@SingleIn(AppScope::class)
class RefreshTracks(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val upsertTrack: UpsertTrack,
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack,
) {

    data class PageTrackerRefreshResult(
        val failures: List<Pair<Tracker?, Throwable>>,
        val nextRefreshIn: Duration,
    )

    private val automaticRefreshMutex = Mutex()
    private val lastAutomaticRefresh = mutableMapOf<Long, TimeMark>()

    /**
     * Fetches updated tracking data from all logged in trackers.
     *
     * @return Failed updates.
     */
    suspend fun await(mangaId: Long): List<Pair<Tracker?, Throwable>> {
        return automaticRefreshMutex.withLock {
            val failures = await(mangaId) { true }
            if (failures.isEmpty()) {
                lastAutomaticRefresh[mangaId] = TimeSource.Monotonic.markNow()
            } else {
                lastAutomaticRefresh.remove(mangaId)
            }
            failures
        }
    }

    suspend fun awaitPageTrackersIfStale(
        mangaId: Long,
        maxAge: Duration = 1.minutes,
    ): PageTrackerRefreshResult {
        // Keep the lock until the pull finishes: readers must not treat an in-flight
        // or cancelled refresh as fresh and overwrite remote progress.
        return automaticRefreshMutex.withLock {
            val elapsed = lastAutomaticRefresh[mangaId]?.elapsedNow()
            if (elapsed != null && elapsed < maxAge) {
                return@withLock PageTrackerRefreshResult(emptyList(), maxAge - elapsed)
            }
            val failures = await(mangaId) { it is PageTracker }
            if (failures.isEmpty()) {
                lastAutomaticRefresh[mangaId] = TimeSource.Monotonic.markNow()
            }
            PageTrackerRefreshResult(failures, maxAge)
        }
    }

    private suspend fun await(
        mangaId: Long,
        serviceFilter: (Tracker) -> Boolean,
    ): List<Pair<Tracker?, Throwable>> {
        return withIOContext {
            supervisorScope {
                return@supervisorScope getTracks.await(mangaId)
                    .map { it to trackerManager.get(it.trackerId) }
                    .filter { (_, service) -> service?.isLoggedIn == true }
                    .filter { (_, service) -> serviceFilter(service!!) }
                    .map { (track, service) ->
                        async {
                            return@async try {
                                val service = service!!
                                val updatedTrack = service.refresh(track.toDbTrack()).toDomainTrack()!!
                                upsertTrack.await(updatedTrack)
                                syncChapterProgressWithTrack.await(mangaId, updatedTrack, service)
                                null
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                service to e
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
        }
    }
}
