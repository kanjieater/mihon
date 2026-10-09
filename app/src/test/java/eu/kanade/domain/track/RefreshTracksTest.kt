package eu.kanade.domain.track

import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack

class RefreshTracksTest {
    private val getTracks = mockk<GetTracks>()
    private val refreshTracks = RefreshTracks(
        getTracks,
        mockk<TrackerManager>(),
        mockk<UpsertTrack>(),
        mockk<SyncChapterProgressWithTrack>(),
    )

    @Test
    fun `automatic refresh is throttled but manual refresh bypasses it`() = runBlocking {
        coEvery { getTracks.await(1L) } returns emptyList()
        refreshTracks.awaitPageTrackersIfStale(1L)
        refreshTracks.awaitPageTrackersIfStale(1L)
        refreshTracks.await(1L)
        refreshTracks.awaitPageTrackersIfStale(1L)
        coVerify(exactly = 2) { getTracks.await(1L) }
    }

    @Test
    fun `reader refresh waits for an in-flight pull`() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { getTracks.await(1L) } coAnswers {
                started.complete(Unit)
                release.await()
                emptyList()
            }
            val automatic = async { refreshTracks.awaitPageTrackersIfStale(1L) }
            started.await()
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                refreshTracks.awaitPageTrackersIfStale(1L)
            }
            assertFalse(reader.isCompleted)
            release.complete(Unit)
            automatic.await()
            reader.await()
            coVerify(exactly = 1) { getTracks.await(1L) }
        }
    }

    @Test
    fun `cancelled pulls are not cached as successful`() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { getTracks.await(1L) } coAnswers {
                started.complete(Unit)
                release.await()
                emptyList()
            }
            val automatic = async { refreshTracks.awaitPageTrackersIfStale(1L) }
            started.await()
            automatic.cancelAndJoin()
            coEvery { getTracks.await(1L) } returns emptyList()
            refreshTracks.awaitPageTrackersIfStale(1L)
            coVerify(exactly = 2) { getTracks.await(1L) }
        }
    }
}
