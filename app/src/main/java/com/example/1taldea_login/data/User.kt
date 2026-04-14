package com.example.osislogin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val email: String,
    val password: String, // This will be hashed
    val fullName: String,
    val pin: String // This will be hashed
)
