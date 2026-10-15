package com.exorcist.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    
    // Сессии
    @Insert
    suspend fun insertSession(session: SensorSession): Long
    
    @Update
    suspend fun updateSession(session: SensorSession)
    
    @Query("SELECT * FROM sensor_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SensorSession>>
    
    @Query("SELECT * FROM sensor_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): SensorSession?
    
    @Query("DELETE FROM sensor_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
    
    // Замеры
    @Insert
    suspend fun insertReading(reading: SensorReading)
    
    @Query("SELECT * FROM sensor_readings WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getReadingsBySession(sessionId: Long): Flow<List<SensorReading>>
    
    @Query("SELECT COUNT(*) FROM sensor_readings WHERE sessionId = :sessionId")
    suspend fun getReadingCount(sessionId: Long): Int
    
    // Контроль роста БД: удаляем старые сессии, оставляем только 100 последних
    @Query("""
        DELETE FROM sensor_sessions 
        WHERE id NOT IN (
            SELECT id FROM sensor_sessions 
            ORDER BY startTime DESC 
            LIMIT 100
        )
    """)
    suspend fun deleteOldSessions()
    
    @Query("""
        DELETE FROM sensor_readings 
        WHERE sessionId NOT IN (
            SELECT id FROM sensor_sessions
        )
    """)
    suspend fun deleteOrphanedReadings()
    
    // Очистка старых данных (вызывать после добавления новой сессии)
    suspend fun cleanup() {
        deleteOldSessions()
        deleteOrphanedReadings()
    }
}
