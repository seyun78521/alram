package com.example.androidlab

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.data.LocalQuestionProvider
import com.example.androidlab.ocr.QuestionImporter
import com.example.androidlab.tts.QuestionReader
import kotlinx.coroutines.launch

// 현재 DB / OCR / TTS 기능을 테스트하기 위한 화면
class MainActivity : AppCompatActivity() {

    private lateinit var importer: QuestionImporter
    private lateinit var reader: QuestionReader
    private lateinit var statusText: TextView

    // 갤러리에서 여러 이미지를 선택
    private val pickImages =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->

            if (uris.isNotEmpty()) {
                importQuestions(uris)
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        // 테스트 화면 연결
        setContentView(
            R.layout.activity_main
        )

        // 문제 이미지 등록 기능
        importer =
            QuestionImporter(
                applicationContext
            )

        // TTS 기능
        reader =
            QuestionReader(
                applicationContext
            )

        // 상태 TextView
        statusText =
            findViewById(
                R.id.statusText
            )

        // 이미지 등록 버튼
        findViewById<Button>(
            R.id.importButton
        ).setOnClickListener {

            pickImages.launch(
                arrayOf("image/*")
            )
        }

        // 랜덤 문제 TTS 버튼
        findViewById<Button>(
            R.id.speakButton
        ).setOnClickListener {

            speakRandomQuestion()
        }
    }

    // 선택한 이미지들을 DB에 저장
    private fun importQuestions(
        uris: List<Uri>
    ) {

        lifecycleScope.launch {

            statusText.text =
                "문제 등록 중..."

            val result =
                importer.importFromUris(uris)

            statusText.text =
                "등록 완료: ${result.saved}개 / 실패: ${result.failed}개"
        }
    }

    // DB에서 랜덤 문제를 가져와 TTS로 읽는다.
    private fun speakRandomQuestion() {

        lifecycleScope.launch {

            // DB에서 문제를 가져오는 객체
            val provider =
                LocalQuestionProvider(
                    applicationContext
                )

            // 랜덤 문제 1개 가져오기
            val question =
                provider
                    .getQuestions(1)
                    .firstOrNull()

            // 문제 없음
            if (question == null) {

                statusText.text =
                    "저장된 문제가 없습니다."

                return@launch
            }

            // 테스트 화면에 문제 표시
            statusText.text =
                question.questionText +
                        "\n\n" +
                        "A. ${question.choiceA}\n" +
                        "B. ${question.choiceB}\n" +
                        "C. ${question.choiceC}"

            // TTS로 문제 읽기
            reader.read(question)
        }
    }

    override fun onDestroy() {

        // 사용이 끝난 기능 정리
        importer.close()
        reader.shutdown()

        super.onDestroy()
    }
}