package com.example.androidlab.data

import android.content.Context

// 문제를 가져오는 기능의 공통 형태
// 나중에 AI 문제 생성 기능을 추가할 때 확장하기 위해 만들어 둔다.
interface QuestionProvider {

    suspend fun getQuestions(
        count: Int
    ): List<Question>
}

// 현재는 로컬 Room DB에서 문제를 가져온다.
class LocalQuestionProvider(
    context: Context
) : QuestionProvider {

    // DB에서 QuestionDao 가져오기
    private val dao =
        AppDatabase.getInstance(context)
            .questionDao()

    // 랜덤 문제 가져오기
    override suspend fun getQuestions(
        count: Int
    ): List<Question> {

        return dao.getRandom(count)
    }
}