package com.telefonechamador.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CallEntity::class,
        SegmentEntity::class,
        SegmentFtsEntity::class,
        BlockedNumberEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    abstract fun segmentDao(): SegmentDao
    abstract fun blockedDao(): BlockedDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "telefone_chamador.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
