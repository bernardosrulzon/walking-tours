package com.walkingtours.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TourEntity::class,
        StopEntity::class,
        StopProgressEntity::class,
        TourProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WalkingToursDatabase : RoomDatabase() {

    abstract fun tourDao(): TourDao

    companion object {
        fun build(context: Context): WalkingToursDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                WalkingToursDatabase::class.java,
                "walking-tours.db",
            )
                // Content ships inside the APK, so a destructive migration on schema change is
                // acceptable for the MVP: progress is the only user data and it is cheap to redo.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
