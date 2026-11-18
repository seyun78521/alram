package com.example.androidlab.data

/*
 * 문제을 "서버 문서 이름"으로 만들어 주는 코드
 * 보기, 정답, 해설은 이름에 넣지 않는다
 */
object QuestionKey {

    fun of(q: Question): String =
        q.questionText
            .replace("/", " ") // 이름에는 "/"를 쓸 수 없다
            .trim()                        // 공백을 지운다
            .replace(Regex("\\s+"), " ")   // 연속된 공백을 하나로 줄인다
            .lowercase()                   // 대소문자 차이를 없앤다
}