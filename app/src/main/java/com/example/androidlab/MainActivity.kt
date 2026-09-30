package com.example.androidlab

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.ocr.BitmapLoader
import com.example.androidlab.ocr.QuestionOcr
import com.example.androidlab.ocr.QuestionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        testNewPage() // 임시 테스트 (확인 후 삭제)
    }

    // ===== 임시 테스트 코드 시작 =====
    private fun testNewPage() {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                resources.openRawResource(R.raw.q2).use { BitmapLoader.decodeUpright(it) }
            }
            // 똑바로 세워졌다면 가로 3000 x 세로 4000 처럼 세로가 더 길게 나온다
            Log.d("PARSE", "이미지 크기: ${bitmap.width} x ${bitmap.height}")

            val ocr = QuestionOcr()
            try {
                // 1) 위치 기준으로 정렬한 줄 전체 (순서 확인용)
                val lines = ocr.recognizeOrdered(bitmap)
                lines.forEachIndexed { i, s -> Log.d("PARSE", "[$i] $s") }

                // 2) 문제로 구조화한 결과 (DB에는 저장하지 않음)
                val parsed = QuestionParser.parse(lines)
                Log.d("PARSE", "===== 구조화된 문제 수: ${parsed.questions.size} =====")
                parsed.questions.forEach { q ->
                    Log.d("PARSE", "문제: ${q.questionText}")
                    Log.d("PARSE", "A: ${q.choiceA} / B: ${q.choiceB} / C: ${q.choiceC}")
                    Log.d("PARSE", "정답: ${q.correctAnswer}")
                }
                parsed.skipped.forEach { Log.d("PARSE", "건너뜀 - $it") }
            } catch (e: Exception) {
                Log.e("PARSE", "실패", e)
            } finally {
                ocr.close()
            }
        }
    }
    // ===== 임시 테스트 코드 끝 =====
}