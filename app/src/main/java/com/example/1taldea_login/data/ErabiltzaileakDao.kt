package com.example.osislogin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ErabiltzaileakDao {
    @Insert
    suspend fun insertErabiltzaileak(erabiltzaileak: Erabiltzaileak)

    @Query("SELECT * FROM erabiltzaileak WHERE erabiltzailea = :erabiltzailea LIMIT 1")
    suspend fun getErabiltzaileakByUsername(erabiltzailea: String): Erabiltzaileak?

    @Query("SELECT * FROM erabiltzaileak")
    suspend fun getAllErabiltzaileak(): List<Erabiltzaileak>

    @Query("SELECT * FROM erabiltzaileak WHERE id = :userId LIMIT 1")
    suspend fun getErabiltzaileakById(userId: Int): Erabiltzaileak?
}