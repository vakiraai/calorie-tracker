package com.liftcal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkoutEntity::class, SetEntity::class, HrSampleEntity::class, ReferenceWindowEntity::class, ProfileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LiftCalDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
    abstract fun hrSamples(): HrSampleDao
    abstract fun referenceWindows(): ReferenceWindowDao
    abstract fun profile(): ProfileDao

    companion object {
        fun create(context: Context): LiftCalDatabase =
            Room.databaseBuilder(context.applicationContext, LiftCalDatabase::class.java, "liftcal.db").build()
    }
}
