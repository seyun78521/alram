package com.example.androidlab

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.data.AppDatabase
import com.example.androidlab.data.Question
import com.example.androidlab.ocr.QuestionImporter
import com.example.androidlab.tts.QuestionReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 시험용 임시 화면. 팀 화면이 만들어지면 이 파일은 대체된다.
 * 흐름: 사진 등록(OCR → 저장) → 정답 입력 → 문제 풀기(읽기 → 3초 대기 → 정오답 표시)
 *
 * 이 파일에 나오는 Kotlin 표기
 *  - lifecycleScope.launch { ... } = "화면이 살아 있는 동안만 도는 백그라운드 작업"을 시작한다.
 *      화면이 닫히면 자동으로 취소된다. DB, OCR, TTS는 오래 걸려서 반드시 이 안에서 불러야 한다.
 *  - 타입 뒤의 ? (예: Question?) = null일 수도 있다는 뜻
 *  - lateinit = "지금은 비어 있지만 onCreate에서 반드시 채운다"는 약속
 *  - { } 로 쓰는 코드 = Java의 람다
 */
class MainActivity : AppCompatActivity() {

    private lateinit var importer: QuestionImporter // 사진 → 문제 저장
    private lateinit var reader: QuestionReader     // 문제 → 소리
    private lateinit var statusText: TextView       // 결과를 보여주는 글자칸

    // 지금 화면이 "문제 풀기" 중인지 알려 주는 모드 값.
    // false = 정답 입력 모드 (A/B/C 를 누르면 정답으로 저장)
    // true  = 문제 풀기 모드 (A/B/C 를 누르면 "내 답"으로 선택만 한다)
    private var isSolving = false

    // 정답 입력 모드에서 지금 보여 주고 있는 문제. 없으면 null.
    private var currentQuestion: Question? = null

    // 문제 풀기 모드에서 지금 푸는 문제와, 사용자가 마지막으로 고른 답("A"/"B"/"C", 안 골랐으면 "")
    private var solvingQuestion: Question? = null
    private var selectedAnswer = ""

    // 답이 확정됐는가? true 가 되면 A/B/C 를 눌러도 답이 바뀌지 않는다.
    private var isConfirmed = false

    // 지금 돌고 있는 "문제 풀기" 작업. 다른 버튼을 누르면 이 작업을 취소해서 읽기를 멈춘다.
    // Job = 백그라운드 작업 하나를 가리키는 손잡이. cancel() 로 취소할 수 있다.
    private var solveJob: Job? = null

    // 사진 여러 장을 고르는 선택 창을 준비해 둔다. 사진을 고르면 { } 안이 실행된다.
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) importQuestions(uris)
        }

    // 화면이 처음 만들어질 때 한 번 실행된다 (Java 안드로이드의 onCreate와 같다)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // activity_main.xml 화면을 붙인다

        importer = QuestionImporter(applicationContext)
        reader = QuestionReader(applicationContext)
        statusText = findViewById(R.id.statusText) // xml에서 id로 부품을 찾아 온다

        // 버튼을 눌렀을 때 할 일을 연결한다.
        findViewById<Button>(R.id.importButton).setOnClickListener {
            stopSolving() // 읽는 중이었다면 멈춘다
            pickImages.launch(arrayOf("image/*")) // 사진 선택 창 열기
        }
        findViewById<Button>(R.id.explanationCheckButton).setOnClickListener { showAllWithExplanation() }
        findViewById<Button>(R.id.answerStartButton).setOnClickListener { showNextWithoutAnswer() }
        findViewById<Button>(R.id.solveButton).setOnClickListener { startSolving() }

        // A/B/C 버튼은 모드에 따라 하는 일이 다르다. 그 구분은 onChoiceClick 에서 한다.
        findViewById<Button>(R.id.answerAButton).setOnClickListener { onChoiceClick("A") }
        findViewById<Button>(R.id.answerBButton).setOnClickListener { onChoiceClick("B") }
        findViewById<Button>(R.id.answerCButton).setOnClickListener { onChoiceClick("C") }
    }

    // 사진 → OCR → DB 저장
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

    // 임시: 저장된 문제 전체를 "문제 / A B C / 정답 / 해설" 순서로 글자로 보여 준다.
    // 해설이 그 문제의 해설이 맞는지 눈으로 대조하는 용도다.
    private fun showAllWithExplanation() {
        stopSolving() // 읽는 중이었다면 멈춘다
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).questionDao()
            val all = dao.getAll() // 저장된 순서(id 순)

            if (all.isEmpty()) {
                statusText.text = "저장된 문제가 없습니다."
                return@launch // return@launch = 이 작업 블록을 끝냄
            }

            // 해설이 채워진 문제가 몇 개인지 센다
            var withExplanation = 0
            for (q in all) {
                if (q.explanation.isNotEmpty()) withExplanation++
            }

            val sb = StringBuilder("전체 ${all.size}개 / 해설 있음 ${withExplanation}개\n\n")
            for (q in all) {
                sb.append("[${q.id}] ${q.questionText}\n")
                sb.append("A. ${q.choiceA}\nB. ${q.choiceB}\nC. ${q.choiceC}\n")
                sb.append("정답: ${q.correctAnswer.ifEmpty { "(미입력)" }}\n") // ifEmpty = 비어 있을 때만 이 값을 쓴다
                sb.append("해설: ${q.explanation.ifEmpty { "(없음)" }}\n")
                sb.append("--------------------\n")
            }
            statusText.text = sb.toString()
        }
    }

    // ===== A/B/C 버튼을 눌렀을 때 =====
    // 모드(isSolving)에 따라 하는 일이 다르다.
    private fun onChoiceClick(letter: String) {
        if (isSolving) {
            selectAnswer(letter) // 문제 풀기 중: 내 답으로 선택
        } else {
            saveAnswer(letter)   // 정답 입력 중: 정답으로 저장
        }
    }

    // ===== 정답 입력 모드 =====

    // 정답이 비어 있는 문제를 하나 꺼내 화면에 보여준다
    private fun showNextWithoutAnswer() {
        stopSolving()     // 읽는 중이었다면 멈춘다
        isSolving = false // 정답 입력 모드로 바꾼다
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

    // 지금 보여준 문제의 정답을 저장하고, 다음 문제를 보여준다
    private fun saveAnswer(answer: String) {
        // 보여준 문제가 없으면(null) 아무것도 하지 않고 끝낸다 (?: return = null이면 함수 종료)
        val question = currentQuestion ?: return

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).questionDao()
            dao.setAnswer(question.id, answer) // DB에 정답 저장
            showNextWithoutAnswer()            // 다음 정답 없는 문제로 넘어간다
        }
    }

    // ===== 문제 풀기 모드 =====

    // 정답이 입력된 문제를 랜덤으로 1개 꺼내 보여 주고, 소리로 읽는다.
    // 다 읽은 뒤 3초를 더 기다렸다가, 마지막에 고른 답으로 확정하고 정답/오답을 표시한다.
    private fun startSolving() {
        stopSolving()    // 앞에서 읽던 것이 있으면 멈춘다
        isSolving = true // 문제 풀기 모드로 바꾼다

        // launch 가 돌려주는 Job 을 solveJob 에 보관해 둔다. (나중에 cancel() 하려고)
        solveJob = lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).questionDao()
            val question = dao.getRandomWithAnswer() // 정답이 있는 문제만 랜덤으로 1개

            solvingQuestion = question
            selectedAnswer = "" // 새 문제이므로 아직 아무것도 고르지 않은 상태
            isConfirmed = false // 아직 확정되지 않음

            // 정답이 입력된 문제가 하나도 없으면 안내하고 끝낸다
            if (question == null) {
                statusText.text = "정답이 입력된 문제가 없습니다. 먼저 정답을 입력하세요"
                return@launch
            }

            showSolveScreen("읽는 중... (A / B / C 를 골라도 됩니다)")

            // 소리로 읽는다. 다 읽을 때까지 여기서 기다린다. (실패하면 false)
            val readOk = reader.read(question)

            // 읽기가 끝난 뒤 3초 동안 더 기다린다. 이 동안에는 답을 바꿀 수 있다.
            // delay(밀리초) = 코루틴을 멈추고 기다린다. 3000 = 3초
            showSolveScreen("읽기 끝! 3초 안에 답을 골라 주세요")
            delay(3000)

            // 3초가 지났으니 마지막에 고른 답으로 확정하고 결과를 보여 준다
            isConfirmed = true
            showResult(question, readOk)
        }
    }

    // 읽던 작업을 멈춘다. (?. = solveJob 이 null 이 아닐 때만 cancel 을 부른다)
    private fun stopSolving() {
        solveJob?.cancel() // 작업을 취소하면 읽기와 3초 기다림이 모두 취소된다
        solveJob = null
    }

    // 문제 풀기 중에 A/B/C 를 누르면 "내 답"으로 기록하고 화면을 다시 그린다.
    // 확정되기 전까지는 여러 번 눌러도 마지막에 누른 것만 남는다.
    private fun selectAnswer(letter: String) {
        // 풀고 있는 문제가 없거나, 이미 확정됐으면 아무것도 하지 않고 끝낸다
        if (solvingQuestion == null || isConfirmed) return

        selectedAnswer = letter
        showSolveScreen("답을 골라 주세요 (마지막에 고른 답으로 확정됩니다)")
    }

    // 문제 풀기 화면을 글자로 그린다. 문제 + 안내 글 + 지금 고른 답
    private fun showSolveScreen(guide: String) {
        val question = solvingQuestion ?: return

        val selected = selectedAnswer.ifEmpty { "(아직 안 골랐음)" }
        statusText.text = guide + "\n\n" + questionToText(question) + "\n\n선택한 답: $selected"
    }

    // 확정 후, 마지막에 고른 답과 정답을 비교해서 결과를 보여 준다
    private fun showResult(question: Question, readOk: Boolean) {
        // 읽기에 실패한 경우(소리가 안 나는 등)에도 결과는 보여 준다. 안내만 덧붙인다.
        val readNote = if (readOk) "" else "(읽기에 실패했습니다. 소리 설정을 확인해 주세요)\n\n"

        val result = when {
            selectedAnswer.isEmpty() -> "답을 고르지 않았습니다. 정답: ${question.correctAnswer}"
            selectedAnswer == question.correctAnswer -> "정답입니다! (내 답: $selectedAnswer)"
            else -> "오답입니다. (내 답: $selectedAnswer / 정답: ${question.correctAnswer})"
        }

        statusText.text = readNote + questionToText(question) + "\n\n" + result
    }

    // 문제를 화면에 보여줄 글로 만든다
    private fun questionToText(q: Question): String =
        "${q.questionText}\n\nA. ${q.choiceA}\nB. ${q.choiceB}\nC. ${q.choiceC}"

    // 화면이 닫힐 때 쓰던 도구들을 정리한다
    override fun onDestroy() {
        reader.shutdown()
        importer.close()
        super.onDestroy()
    }
}