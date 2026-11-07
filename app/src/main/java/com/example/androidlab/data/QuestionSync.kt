package com.example.androidlab.data

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import com.google.firebase.firestore.Source

// Room(내 폰)과 Firestore(서버)를 오가는 코드를 모아 두는 곳.
// 이번 6-2에서는 "올리기"만 만들고, 6-3에서 "내려받기"를 이 파일에 추가한다.
object QuestionSync {

    // Firestore 안의 컬렉션 이름. 폴더 이름이라고 생각하면 된다. (const = 바뀌지 않는 상수)
    private const val COLLECTION = "questions"

    // 로그인이 안 되어 있으면 익명 로그인을 한다. (5단계에서 켜 둔 기능)
    // suspend = 시간이 걸려서 코루틴 안에서만 부를 수 있는 함수.
    private suspend fun ensureSignedIn() {
        val auth = Firebase.auth                 // 로그인 관리자
        if (auth.currentUser == null) {          // 아직 로그인한 적 없으면
            auth.signInAnonymously().await()     // 익명 로그인하고, 끝날 때까지 기다린다
        }
    }

    // 내 폰의 문제를 Firestore에 올리고, 올린 개수를 돌려준다.
    // dao = Room에서 문제를 꺼내 오는 도구 (QuestionDao)
    suspend fun upload(dao: QuestionDao): Int {
        ensureSignedIn()

        // 정답이 입력된 문제만 올린다. (정답 없는 문제는 남에게 쓸모가 적어서)
        // filter = 조건에 맞는 것만 남긴 새 리스트를 만든다.
        val targets = dao.getAll().filter { it.correctAnswer.isNotEmpty() }

        val db = Firebase.firestore

        // chunked(400) = 리스트를 400개씩 묶음으로 자른다.
        // Firestore의 batch(한꺼번에 쓰기)는 한 번에 최대 500개까지라서 여유 있게 400으로 자른다.
        for (chunk in targets.chunked(400)) {
            val batch = db.batch()               // 여러 쓰기를 모아 한 번에 보내는 꾸러미

            for (q in chunk) {
                // 문서 위치 = questions/{해시}. 같은 문제는 같은 주소라서 덮어쓰기가 되고 중복이 안 생긴다.
                val ref = db.collection(COLLECTION).document(QuestionHash.of(q))

                // 서버에 저장할 글자들. 사진과 Room의 id는 올리지 않는다.
                // mapOf("키" to 값, ...) = Java의 HashMap 비슷한 것
                batch.set(
                    ref,
                    mapOf(
                        "questionText" to q.questionText,
                        "choiceA" to q.choiceA,
                        "choiceB" to q.choiceB,
                        "choiceC" to q.choiceC,
                        "correctAnswer" to q.correctAnswer,
                        "explanation" to q.explanation,
                        "createdAt" to q.createdAt
                    )
                )
            }

            // 10초 안에 서버가 "받았다"고 답하지 않으면 null이 되어 에러로 바꾼다.
            // (commit().await() 자체는 성공해도 값이 없으므로, 성공 표시로 true를 따로 돌려준다)
            withTimeoutOrNull(10_000) { batch.commit().await(); true }
                ?: throw IllegalStateException("서버 응답이 없습니다")
        }

        return targets.size
    }
    // Firestore의 문제를 내 폰(Room)으로 내려받고, 새로 저장한 개수를 돌려준다.
    // 이미 내 폰에 있는 문제는 건너뛴다. (같은 문제 = 해시가 같은 문제)
    suspend fun download(dao: QuestionDao): Int {
        ensureSignedIn()

        // Source.SERVER = 오프라인이면 캐시로 넘기지 않고 실패한다
        val snapshot = Firebase.firestore.collection(COLLECTION).get(Source.SERVER).await()

        // 내 폰에 이미 있는 문제들의 해시를 모아 둔다. (중복 확인용 목록)
        // toMutableSet() = 나중에 값을 추가할 수 있는 집합(Set)으로 만든다. 집합은 같은 값이 한 번만 들어간다.
        val localHashes = dao.getAll().map { QuestionHash.of(it) }.toMutableSet()

        var added = 0                              // var = 값을 바꿀 수 있는 변수 (val은 못 바꿈)

        for (doc in snapshot.documents) {          // 문서 하나(문제 하나)씩 처리
            // 서버 문서에서 글자를 꺼내 Question 객체로 만든다.
            // getString은 값이 없으면 null을 준다. "?: continue" = null이면 이 문서는 건너뛰고 다음 문서로.
            // (서버에 이상한 문서가 섞여 있어도 앱이 죽지 않게 하는 안전장치)
            val q = Question(
                questionText = doc.getString("questionText") ?: continue,
                choiceA = doc.getString("choiceA") ?: continue,
                choiceB = doc.getString("choiceB") ?: continue,
                choiceC = doc.getString("choiceC") ?: continue,
                correctAnswer = doc.getString("correctAnswer") ?: "",
                explanation = doc.getString("explanation") ?: "",
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            )

            // set.add()는 새로 들어가면 true, 이미 있으면 false를 돌려준다.
            // 즉 "처음 보는 문제일 때만" 아래가 실행된다.
            if (localHashes.add(QuestionHash.of(q))) {
                dao.insert(q)                      // Room에 저장
                added++                            // 개수 +1
            }
        }
        return added
    }
}