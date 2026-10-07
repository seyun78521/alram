package com.example.androidlab.data

import android.content.Context

// interface = "이런 함수를 가져야 한다"는 약속만 적은 것 (Java의 interface와 같다).
// 문제를 어디서 가져오는지는 몰라도, 이 약속만 보고 쓸 수 있다.
// 나중에 AI가 만든 문제를 가져오는 클래스를 추가해도, 쓰는 쪽 코드는 바꾸지 않아도 된다.
interface QuestionProvider {

    // 문제를 count개 돌려준다.
    // suspend = 시간이 걸리는 작업이라 코루틴(백그라운드 작업) 안에서만 부를 수 있다.
    suspend fun getQuestions(count: Int): List<Question>
}

// 지금 쓰는 구현: 폰 안의 Room DB에서 랜덤으로 문제를 가져온다.
// ": QuestionProvider" = 위 interface를 구현한다는 뜻 (Java의 implements)
class LocalQuestionProvider(context: Context) : QuestionProvider {

    // DB에서 문제를 꺼내는 도구(DAO)를 가져온다
    private val dao = AppDatabase.getInstance(context).questionDao()

    // override = interface에 적힌 함수를 실제로 만든다는 표시
    override suspend fun getQuestions(count: Int): List<Question> = dao.getRandom(count)
}