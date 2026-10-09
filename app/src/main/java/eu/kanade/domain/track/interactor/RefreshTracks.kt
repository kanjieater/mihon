package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.PageTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class RefreshTracks(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
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
        return await(mangaId) { true }
    }

    suspend fun awaitPageTrackersIfStale(
        mangaId: Long,
        maxAge: Duration = 1.minutes,
    ): PageTrackerRefreshResult {
        val (shouldRefresh, nextRefreshIn) = automaticRefreshMutex.withLock {
            val lastRefresh = lastAutomaticRefresh[mangaId]
            val elapsed = lastRefresh?.elapsedNow()
            if (elapsed != null && elapsed < maxAge) {
                false to (maxAge - elapsed)
            } else {
                lastAutomaticRefresh[mangaId] = TimeSource.Monotonic.markNow()
                true to maxAge
            }
        }
        val failures = if (shouldRefresh) await(mangaId) { it is PageTracker } else emptyList()
        return PageTrackerRefreshResult(failures, nextRefreshIn)
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
                                insertTrack.await(updatedTrack)
                                syncChapterProgressWithTrack.await(mangaId, updatedTrack, service)
                                null
                            } catch (e: Throwable) {
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
