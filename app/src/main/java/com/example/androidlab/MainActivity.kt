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

/*
 * 현재는 DB/OCR/TTS 기능 테스트용 화면
 */
class MainActivity : AppCompatActivity() {

    private lateinit var importer: QuestionImporter
    private lateinit var reader: QuestionReader
    private lateinit var statusText: TextView

    /*
     * 갤러리에서 이미지 여러 장 선택
     */
    private val pickImages =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->

            if (
                uris.isNotEmpty()
            ) {
                importQuestions(uris)
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        importer =
            QuestionImporter(
                applicationContext
            )

        reader =
            QuestionReader(
                applicationContext
            )

        statusText =
            findViewById(
                R.id.statusText
            )

        /*
         * 문제 이미지 등록
         */
        findViewById<Button>(
            R.id.importButton
        ).setOnClickListener {

            pickImages.launch(
                arrayOf("image/*")
            )
        }

        /*
         * 랜덤 문제 TTS
         */
        findViewById<Button>(
            R.id.speakButton
        ).setOnClickListener {

            speakRandomQuestion()
        }
    }

    /*
     * 이미지 -> OCR -> DB
     */
    private fun importQuestions(
        uris: List<Uri>
    ) {

        lifecycleScope.launch {

            statusText.text =
                "문제 등록 중..."

            val result =
                importer.importFromUris(
                    uris
                ) { done, total ->

                    statusText.text =
                        "문제 등록 중... " +
                                "$done / $total"
                }

            /*
             * 최종 결과 표시
             */
            statusText.text =
                buildString {

                    append(
                        "등록 완료\n"
                    )

                    append(
                        "저장된 문제: " +
                                "${result.saved}개\n"
                    )

                    append(
                        "이미지 실패: " +
                                "${result.failed.size}장\n"
                    )

                    append(
                        "문제 인식 실패: " +
                                "${result.skipped.size}개"
                    )

                    if (
                        result.failed.isNotEmpty()
                    ) {

                        append(
                            "\n\n[이미지 실패]\n"
                        )

                        append(
                            result.failed
                                .joinToString("\n")
                        )
                    }

                    if (
                        result.skipped.isNotEmpty()
                    ) {

                        append(
                            "\n\n[문제 인식 실패]\n"
                        )

                        append(
                            result.skipped
                                .joinToString("\n")
                        )
                    }
                }
        }
    }

    /*
     * DB에서 랜덤 문제 1개 가져오기
     */
    private fun speakRandomQuestion() {

        lifecycleScope.launch {

            val provider =
                LocalQuestionProvider(
                    applicationContext
                )

            val question =
                provider
                    .getQuestions(1)
                    .firstOrNull()

            if (
                question == null
            ) {

                statusText.text =
                    "저장된 문제가 없습니다."

                return@launch
            }

            /*
             * 화면에 실제 저장된 데이터 표시
             */
            statusText.text =
                "${question.questionText}\n\n" +
                        "A. ${question.choiceA}\n" +
                        "B. ${question.choiceB}\n" +
                        "C. ${question.choiceC}"

            /*
             * TTS
             */
            reader.read(
                question
            )
        }
    }

    override fun onDestroy() {

        importer.close()

        reader.shutdown()

        super.onDestroy()
    }
}