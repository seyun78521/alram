package com.example.androidlab.data

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

// Room과 Firestore를 오가는 코드. upload() / download()만 호출하면 된다.
// 서버에는 questions 컬렉션 안에 문제 하나가 문서 하나로 저장된다. (문서 이름은 QuestionHash값)
object QuestionSync {

    // Firestore 안의 컬렉션 이름. (const = 바뀌지 않는 상수)
    private const val COLLECTION = "questions"

    // 로그인이 안 되어 있으면 익명 로그인을 한다. (보안 규칙이 로그인한 사용자만 허용하기 때문)
    private suspend fun ensureSignedIn() {
        val auth = Firebase.auth                 // 로그인 관리자
        if (auth.currentUser == null) {          // 로그인한 적 없으면
            auth.signInAnonymously().await()     // 익명 로그인한다
        }
    }

    // ===== 내 폰 → 서버 =====
    // 정답이 입력된 문제를 서버에 올림
    suspend fun upload(dao: QuestionDao): Int {
        ensureSignedIn()

        // 1) 정답이 있는 문제만 고른다. filter { 조건 }
        val targets = dao.getAll().filter { it.correctAnswer.isNotEmpty() }

        val db = Firebase.firestore

        // 2) 400개씩 묶어서 보낸다. (최대가 500개라서 여유 있게)
        for (chunk in targets.chunked(400)) {
            val batch = db.batch()  // 여러 문제를 모아 한 번에 보냄

            for (q in chunk) {
                // 문서 위치 = questions/{해시}
                val ref = db.collection(COLLECTION).document(QuestionHash.of(q))

                // 서버에 저장할 글자들 바꾸면 보안규칙도 같이 고쳐야 한다
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

            // 3) 서버로 보낸다. 10초 안에 응답이 없으면 오류로 처리
            withTimeoutOrNull(10_000) { batch.commit().await(); true }
                ?: throw IllegalStateException("서버 응답이 없습니다")
        }

        return targets.size
    }

    // ===== 서버 → 내 폰 =====
    // 서버의 문제 중 Room에 없는 것만 저장
    suspend fun download(dao: QuestionDao): Int {
        ensureSignedIn()

        // 1) 서버의 문서를 전부 가져온다
        val snapshot = Firebase.firestore.collection(COLLECTION).get(Source.SERVER).await()

        // 2) Room에 있는 문제들의 해시를 모아 둔다
        val localHashes = dao.getAll().map { QuestionHash.of(it) }.toMutableSet()

        var added = 0

        for (doc in snapshot.documents) {   // 문서(= 문제) 하나씩 처리한다
            // 3) 서버 문서를 Question으로 만든다 (?: continue = null이면 다음 문서로)
            val q = Question(
                questionText = doc.getString("questionText") ?: continue,
                choiceA = doc.getString("choiceA") ?: continue,
                choiceB = doc.getString("choiceB") ?: continue,
                choiceC = doc.getString("choiceC") ?: continue,
                correctAnswer = doc.getString("correctAnswer") ?: "",
                explanation = doc.getString("explanation") ?: "",
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            )

            // 4) 없는 문제만 저장한다 (Set.add는 새로 들어가면 true, 이미 있으면 false)
            if (localHashes.add(QuestionHash.of(q))) {
                dao.insert(q)   // Room에 저장
                added++
            }
        }
        return added
    }
}