package com.example.androidlab.data

import android.content.Context

// 다른 기능이 문제를 받아가는 창구. "어디서 가져오는지"는 몰라도 된다.
interface QuestionProvider {
    // count개의 문제를 돌려준다. category를 주면 그 카테고리에서만 (예: "Part 3")
    suspend fun getQuestions(count: Int, category: String? = null): List<Question>
}

// 지금 쓰는 구현: 폰 안의 Room DB에서 랜덤으로 가져온다
class LocalQuestionProvider(context: Context) : QuestionProvider {

    private val dao = AppDatabase.getInstance(context).questionDao()

    override suspend fun getQuestions(count: Int, category: String?): List<Question> {
        return if (category == null) {
            dao.getRandom(count)
        } else {
            dao.getRandomByCategory(category, count)
        }
    }
}