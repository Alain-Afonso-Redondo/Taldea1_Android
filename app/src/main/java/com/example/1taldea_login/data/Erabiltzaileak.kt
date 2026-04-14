package com.example.osislogin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "erabiltzaileak")
data class Erabiltzaileak(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val erabiltzailea: String,  // nombre de usuario
    val pasahitza: String       // contraseña (pin)
)