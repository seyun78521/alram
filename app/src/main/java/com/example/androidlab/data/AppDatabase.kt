package com.example.androidlab.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 앱 전체에서 쓰는 Room 데이터베이스.
// entities = 이 DB 안에 만들 표(테이블) 목록. 지금은 Question 표 하나뿐이다.
// version = DB 구조의 버전 번호. 구조를 바꾸면 올려야 하지만, 미니멈은 앱 삭제 후 재설치로 해결한다.
// exportSchema = false : 구조 설명 파일을 따로 내보내지 않는다.
@Database(entities = [Question::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Question 표를 다루는 DAO를 돌려준다. (함수 몸체는 Room이 자동으로 만들어 준다)
    abstract fun questionDao(): QuestionDao

    // companion object = Java의 static 멤버와 비슷하다.
    // AppDatabase.getInstance(...) 처럼 객체를 만들지 않고 바로 부를 수 있다.
    companion object {

        // DB 객체를 하나만 만들어 계속 재사용한다. (싱글톤)
        // DB를 여러 개 만들면 메모리 낭비이고 오류가 날 수 있기 때문이다.
        // @Volatile = 여러 스레드가 항상 최신 값을 보게 한다.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // DB를 가져온다. 처음 부르면 만들고, 그다음부터는 만들어 둔 것을 돌려준다.
        fun getInstance(context: Context): AppDatabase {
            // ?: 는 "왼쪽이 null이면 오른쪽 실행". 이미 만들어져 있으면 바로 돌려준다.
            return INSTANCE ?: synchronized(this) {
                // synchronized = 한 번에 한 스레드만 들어오게 막는다. (동시에 두 개 만드는 것을 방지)
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, // 화면이 닫혀도 유지되도록 앱 전체의 context를 쓴다
                    AppDatabase::class.java,
                    "question_database"          // 폰에 저장될 DB 파일 이름
                ).build().also { INSTANCE = it } // 만든 것을 INSTANCE에 보관
            }
        }
    }
}