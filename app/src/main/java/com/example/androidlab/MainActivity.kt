package com.example.androidlab

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.data.AppDatabase
import com.example.androidlab.data.LocalQuestionProvider
import com.example.androidlab.data.Question
import com.example.androidlab.ocr.QuestionImporter
import com.example.androidlab.tts.QuestionReader
import kotlinx.coroutines.launch

// 시험용 임시 화면. 팀 화면이 만들어지면 이 파일은 대체된다.
class MainActivity : AppCompatActivity() {

    // lateinit = "지금은 비어 있지만 onCreate에서 반드시 채운다"는 약속
    private lateinit var importer: QuestionImporter // 사진 → 문제 저장
    private lateinit var reader: QuestionReader     // 문제 → 소리
    private lateinit var statusText: TextView       // 결과를 보여주는 글자칸

    // [추가] 지금 정답을 입력받고 있는 문제. 없으면 null.
    private var currentQuestion: Question? = null

    // 사진 여러 장을 고르는 선택 창. 사진을 고르면 { } 안이 실행된다.
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) importQuestions(uris)
        }

    // 화면이 처음 만들어질 때 한 번 실행된다
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        importer = QuestionImporter(applicationContext)
        reader = QuestionReader(applicationContext)
        statusText = findViewById(R.id.statusText)

        // 버튼을 눌렀을 때 할 일을 연결한다
        findViewById<Button>(R.id.importButton).setOnClickListener {
            pickImages.launch(arrayOf("image/*")) // 사진 선택 창 열기
        }
        findViewById<Button>(R.id.speakButton).setOnClickListener { speakRandomQuestion() }

        // [추가] 정답 입력 관련 버튼
        findViewById<Button>(R.id.answerStartButton).setOnClickListener { showNextWithoutAnswer() }
        findViewById<Button>(R.id.answerAButton).setOnClickListener { saveAnswer("A") }
        findViewById<Button>(R.id.answerBButton).setOnClickListener { saveAnswer("B") }
        findViewById<Button>(R.id.answerCButton).setOnClickListener { saveAnswer("C") }
    }

    // 사진 → OCR → DB 저장. 오래 걸리므로 lifecycleScope.launch(백그라운드 작업) 안에서 한다.
    private fun importQuestions(uris: List<Uri>) {
        lifecycleScope.launch {
            statusText.text = "문제 등록 중..."

            // 처리하는 동안 진행 상황({ done, total } 부분)을 화면에 보여준다
            val result = importer.importFromUris(uris) { done, total ->
                statusText.text = "문제 등록 중... $done / $total"
            }

            // 결과 정리. buildString = 글자를 이어 붙여 하나의 문자열로 만든다.
            statusText.text = buildString {
                append("등록 완료\n")
                append("저장된 문제: ${result.saved}개\n")
                append("이미지 실패: ${result.failed.size}장\n")
                append("문제 인식 실패: ${result.skipped.size}개")
                if (result.failed.isNotEmpty()) {
                    append("\n\n[이미지 실패]\n${result.failed.joinToString("\n")}")
                }
                if (result.skipped.isNotEmpty()) {
                    append("\n\n[문제 인식 실패]\n${result.skipped.joinToString("\n")}")
                }
            }
        }
    }

    // DB에서 랜덤 문제 1개를 꺼내 화면에 보여주고 소리로 읽는다
    private fun speakRandomQuestion() {
        lifecycleScope.launch {
            val question = LocalQuestionProvider(applicationContext).getQuestions(1).firstOrNull()

            // 문제가 하나도 없으면 안내하고 끝낸다 (return@launch = 이 작업 블록을 끝냄)
            if (question == null) {
                statusText.text = "저장된 문제가 없습니다."
                return@launch
            }

            statusText.text = questionToText(question)
            reader.read(question) // 소리로 읽기
        }
    }

    // [추가] 정답이 비어 있는 문제를 하나 꺼내 화면에 보여준다
    private fun showNextWithoutAnswer() {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).questionDao()
            val question = dao.getNextWithoutAnswer()
            currentQuestion = question // 어떤 문제의 정답을 받는 중인지 기억해 둔다

            statusText.text = if (question == null) {
                "정답을 입력할 문제가 없습니다. (모두 입력했거나 문제가 없음)"
            } else {
                "정답을 눌러 주세요 (책을 보고 A / B / C)\n\n" + questionToText(question)
            }
        }
    }

    // [추가] 지금 보여준 문제의 정답을 저장하고, 다음 문제를 보여준다
    private fun saveAnswer(answer: String) {
        // 보여준 문제가 없으면(null) 아무것도 하지 않고 끝낸다
        val question = currentQuestion ?: return

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).questionDao()
            dao.setAnswer(question.id, answer) // DB에 정답 저장
            showNextWithoutAnswer()            // 다음 정답 없는 문제로 넘어간다
        }
    }

    // 문제를 화면에 보여줄 글로 만든다 (사진은 보여주지 않는다)
    private fun questionToText(q: Question): String =
        "${q.questionText}\n\nA. ${q.choiceA}\nB. ${q.choiceB}\nC. ${q.choiceC}"

    // 화면이 닫힐 때 쓰던 도구들을 정리한다
    override fun onDestroy() {
        importer.close()
        reader.shutdown()
        super.onDestroy()
    }
}