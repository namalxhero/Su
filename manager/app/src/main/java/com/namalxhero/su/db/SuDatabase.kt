package com.namalxhero.su.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class GrantTypeConverters {
    @TypeConverter
    fun fromGrantType(value: GrantType): String = value.name

    @TypeConverter
    fun toGrantType(value: String): GrantType = GrantType.valueOf(value)
}

@Database(entities = [AllowlistEntry::class], version = 1, exportSchema = false)
@TypeConverters(GrantTypeConverters::class)
abstract class SuDatabase : RoomDatabase() {
    abstract fun allowlistDao(): AllowlistDao

    companion object {
        @Volatile private var instance: SuDatabase? = null

        fun get(context: Context): SuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SuDatabase::class.java,
                    "su_manager.db",
                ).build().also { instance = it }
            }
    }
}
