package com.example.osislogin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.osislogin.util.HashingUtil

@Database(entities = [User::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add the pin column to the users table
                database.execSQL("ALTER TABLE users ADD COLUMN pin TEXT NOT NULL DEFAULT ''")
                
                // Update existing users with default PINs (hashed)
                val hashUtil = HashingUtil()
                val adminPinHash = hashUtil.hashPassword("1234")
                val userPinHash = hashUtil.hashPassword("0000")
                
                database.execSQL("UPDATE users SET pin = '$adminPinHash' WHERE email = 'admin@example.com'")
                database.execSQL("UPDATE users SET pin = '$userPinHash' WHERE email = 'user@example.com'")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "osis_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Pre-populate database
                            val hashUtil = HashingUtil()
                            val adminPinHash = hashUtil.hashPassword("1234")
                            val userPinHash = hashUtil.hashPassword("0000")
                            db.execSQL(
                                "INSERT INTO users (email, password, fullName, pin) VALUES ('admin@example.com', '${hashUtil.hashPassword("123456")}', 'Administrador', '$adminPinHash')"
                            )
                            db.execSQL(
                                "INSERT INTO users (email, password, fullName, pin) VALUES ('user@example.com', '${hashUtil.hashPassword("password")}', 'Usuario Prueba', '$userPinHash')"
                            )
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
