package com.walkingtours.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tours")
data class TourEntity(
    @PrimaryKey val id: String,
    val title: String,
    val city: String,
    val country: String,
    /** One-line pitch shown on the tour list card. */
    val summary: String,
    /** Spoken introduction to the city and the walk, played before the first stop. */
    val overviewText: String,
    val distanceKm: Double,
    val totalWalkMinutes: Int,
    val difficulty: String,
    val bestTimeOfDay: String,
    val heroImage: String?,
    /** Attribution for bundled imagery, shown in an about box so licences are honoured. */
    val imageCredits: String,
)

@Entity(
    tableName = "stops",
    foreignKeys = [
        ForeignKey(
            entity = TourEntity::class,
            parentColumns = ["id"],
            childColumns = ["tourId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tourId")],
)
data class StopEntity(
    @PrimaryKey val id: String,
    val tourId: String,
    /** 1-based position along the walking route. */
    val order: Int,
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    /** Full audio transcript; also what the text-to-speech engine reads aloud. */
    val narration: String,
    val suggestedMinutes: Int,
    val entranceFeeTry: String,
    val entranceFeeNote: String,
    val isFree: Boolean,
    val openingHours: String,
    val accessibility: String,
    val insiderTip: String,
    /** Spoken directions from this stop to the next. */
    val nextStopDirections: String,
    val photoAsset: String?,
    val photoAttribution: String?,
    /**
     * Geofence arrival radius. Tight in crowded squares, wider for sprawling sites.
     */
    val triggerRadiusMeters: Int,
)

/** Per-stop completion tracking, so a tour can be resumed across app restarts. */
@Entity(tableName = "stop_progress", primaryKeys = ["tourId", "stopId"])
data class StopProgressEntity(
    val tourId: String,
    val stopId: String,
    val visitedAtEpochMs: Long,
    val audioCompleted: Boolean,
)

@Entity(tableName = "tour_progress")
data class TourProgressEntity(
    @PrimaryKey val tourId: String,
    val startedAtEpochMs: Long,
    val lastStopId: String?,
    val completedAtEpochMs: Long?,
)

/** Projection for the tour list, which needs a stop count without loading every stop row. */
data class TourStopCount(
    val tourId: String,
    val stopCount: Int,
)
