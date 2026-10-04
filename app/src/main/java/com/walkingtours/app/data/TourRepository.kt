package com.walkingtours.app.data

import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.StopProgressEntity
import com.walkingtours.app.data.db.TourDao
import com.walkingtours.app.data.db.TourEntity
import com.walkingtours.app.data.db.TourProgressEntity
import com.walkingtours.app.data.db.TourStopCount
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point to tour content and the user's progress through it.
 * The UI never touches the DAO directly.
 */
class TourRepository(
    private val dao: TourDao,
    private val seeder: ContentSeeder,
) {

    suspend fun ensureContentLoaded() = seeder.seedIfEmpty()

    fun observeTours(): Flow<List<TourEntity>> = dao.observeTours()

    fun observeStopCounts(): Flow<List<TourStopCount>> = dao.observeStopCounts()

    fun observeTour(tourId: String): Flow<TourEntity?> = dao.observeTour(tourId)

    fun observeStops(tourId: String): Flow<List<StopEntity>> = dao.observeStops(tourId)

    fun observeStopProgress(tourId: String): Flow<List<StopProgressEntity>> =
        dao.observeStopProgress(tourId)

    fun observeTourProgress(tourId: String): Flow<TourProgressEntity?> =
        dao.observeTourProgress(tourId)

    suspend fun getStops(tourId: String): List<StopEntity> = dao.getStops(tourId)

    suspend fun getTour(tourId: String): TourEntity? = dao.getTour(tourId)

    suspend fun getStop(stopId: String): StopEntity? = dao.getStop(stopId)

    suspend fun startOrResumeTour(tourId: String, firstStopId: String?) {
        val existing = dao.getTourProgress(tourId)
        dao.upsertTourProgress(
            TourProgressEntity(
                tourId = tourId,
                // Preserve the original start time when resuming a tour the user already began.
                startedAtEpochMs = existing?.startedAtEpochMs ?: System.currentTimeMillis(),
                lastStopId = firstStopId ?: existing?.lastStopId,
                completedAtEpochMs = null,
            ),
        )
    }

    /** Records that the walker physically reached a stop, and moves the "last stop" marker. */
    suspend fun recordArrival(tourId: String, stopId: String, audioCompleted: Boolean = false) {
        dao.upsertStopProgress(
            StopProgressEntity(
                tourId = tourId,
                stopId = stopId,
                visitedAtEpochMs = System.currentTimeMillis(),
                audioCompleted = audioCompleted,
            ),
        )
        val existing = dao.getTourProgress(tourId)
        dao.upsertTourProgress(
            TourProgressEntity(
                tourId = tourId,
                startedAtEpochMs = existing?.startedAtEpochMs ?: System.currentTimeMillis(),
                lastStopId = stopId,
                completedAtEpochMs = null,
            ),
        )
    }

    suspend fun visitedStopIds(tourId: String): Set<String> =
        dao.getStopProgress(tourId).map { it.stopId }.toSet()

    /**
     * Every photograph's credit, for the Settings screen. The photographs are Creative Commons, which
     * requires attribution, but the credit does not belong under every photo in the reading flow, so
     * it is collected in one place instead.
     */
    suspend fun photoCredits(): List<StopEntity> = dao.stopsWithPhotoCredits()

    /**
     * Tick or untick a stop by hand, from the checkbox beside its name. Unlike [recordArrival] this
     * makes no claim about the walker being there, so it can be used before the trip as well.
     */
    suspend fun setStopVisited(tourId: String, stopId: String, isVisited: Boolean) {
        if (isVisited) {
            dao.upsertStopProgress(
                StopProgressEntity(
                    tourId = tourId,
                    stopId = stopId,
                    visitedAtEpochMs = System.currentTimeMillis(),
                    audioCompleted = false,
                ),
            )
        } else {
            dao.clearStopProgressForStop(tourId, stopId)
        }
    }

    suspend fun resetTour(tourId: String) {
        dao.clearStopProgress(tourId)
        dao.clearTourProgress(tourId)
    }
}
