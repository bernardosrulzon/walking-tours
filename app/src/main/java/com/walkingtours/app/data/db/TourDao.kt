package com.walkingtours.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TourDao {

    @Query("SELECT * FROM tours ORDER BY city, title")
    fun observeTours(): Flow<List<TourEntity>>

    @Query("SELECT * FROM tours WHERE id = :tourId")
    fun observeTour(tourId: String): Flow<TourEntity?>

    @Query("SELECT * FROM tours WHERE id = :tourId")
    suspend fun getTour(tourId: String): TourEntity?

    @Query("SELECT * FROM stops WHERE tourId = :tourId ORDER BY `order` ASC")
    fun observeStops(tourId: String): Flow<List<StopEntity>>

    @Query("SELECT * FROM stops WHERE tourId = :tourId ORDER BY `order` ASC")
    suspend fun getStops(tourId: String): List<StopEntity>

    @Query("SELECT * FROM stops WHERE id = :stopId")
    suspend fun getStop(stopId: String): StopEntity?

    @Query("SELECT * FROM stop_progress WHERE tourId = :tourId")
    fun observeStopProgress(tourId: String): Flow<List<StopProgressEntity>>

    @Query("SELECT * FROM stop_progress WHERE tourId = :tourId")
    suspend fun getStopProgress(tourId: String): List<StopProgressEntity>

    @Query("SELECT * FROM tour_progress WHERE tourId = :tourId")
    fun observeTourProgress(tourId: String): Flow<TourProgressEntity?>

    @Query("SELECT * FROM tour_progress WHERE tourId = :tourId")
    suspend fun getTourProgress(tourId: String): TourProgressEntity?

    @Query("SELECT * FROM tour_progress")
    suspend fun getAllTourProgress(): List<TourProgressEntity>

    @Query("SELECT COUNT(*) FROM tours")
    suspend fun tourCount(): Int

    @Query("SELECT tourId, COUNT(*) AS stopCount FROM stops GROUP BY tourId")
    fun observeStopCounts(): Flow<List<TourStopCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTours(tours: List<TourEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(stops: List<StopEntity>)

    @Upsert
    suspend fun upsertStopProgress(progress: StopProgressEntity)

    @Upsert
    suspend fun upsertTourProgress(progress: TourProgressEntity)

    @Query("DELETE FROM stop_progress WHERE tourId = :tourId")
    suspend fun clearStopProgress(tourId: String)

    @Query("DELETE FROM stop_progress WHERE tourId = :tourId AND stopId = :stopId")
    suspend fun clearStopProgressForStop(tourId: String, stopId: String)

    @Query(
        "SELECT * FROM stops WHERE photoAttribution IS NOT NULL AND photoAttribution != '' " +
            "ORDER BY tourId, `order`",
    )
    suspend fun stopsWithPhotoCredits(): List<StopEntity>

    @Query("DELETE FROM tour_progress WHERE tourId = :tourId")
    suspend fun clearTourProgress(tourId: String)

    /** Wipes the bundled content so it can be re-seeded after a content update. */
    @Query("DELETE FROM stops")
    suspend fun clearAllStops()

    @Query("DELETE FROM tours")
    suspend fun clearAllTours()
}
