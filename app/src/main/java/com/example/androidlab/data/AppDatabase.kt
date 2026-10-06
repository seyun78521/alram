package com.example.androidlab.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 앱에서 사용하는 Room 데이터베이스
@Database(
    entities = [Question::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    // Question 테이블에 접근하기 위한 DAO
    abstract fun questionDao(): QuestionDao

    companion object {

        // 앱에서 DB를 하나만 사용하기 위해 저장해 둔다.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // DB 가져오기
        fun getInstance(context: Context): AppDatabase {

            return INSTANCE ?: synchronized(this) {

                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "question_database"
                )
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }
    }
}