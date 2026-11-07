package com.namalxhero.su.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowlistDao {

    @Query("SELECT * FROM allowlist ORDER BY grantedAtMillis DESC")
    fun observeAll(): Flow<List<AllowlistEntry>>

    @Query("SELECT * FROM allowlist")
    suspend fun getAll(): List<AllowlistEntry>

    @Query("SELECT * FROM allowlist WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: Int): AllowlistEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AllowlistEntry)

    @Query("DELETE FROM allowlist WHERE uid = :uid")
    suspend fun deleteByUid(uid: Int)
}
