package com.example.androidlab

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.data.AppDatabase
import com.example.androidlab.ocr.BatchImporter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var batchImporter: BatchImporter
    private lateinit var btnImport: Button
    private lateinit var tvStatus: TextView

    // 파일/갤러리에서 사진 여러 장을 고르는 기본 선택 창
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) startImport(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        batchImporter = BatchImporter(applicationContext)
        btnImport = findViewById(R.id.btnImport)
        tvStatus = findViewById(R.id.tvStatus)

        btnImport.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
    }

    // ===== 임시 테스트 화면 코드 (팀원의 화면이 생기면 대체) =====
    private fun startImport(uris: List<Uri>) {
        btnImport.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = batchImporter.importFromUris(uris) { done, total ->
                    tvStatus.text = "처리 중... $done / $total"
                }
                val total = AppDatabase.getInstance(applicationContext).questionDao().count()
                tvStatus.text = "완료\n" +
                        "저장 ${result.saved}개 / 중복 ${result.duplicates}개\n" +
                        "건너뜀 ${result.skipped.size}개 / 읽기 실패 ${result.failedImages.size}장\n" +
                        "DB 전체 문제 수: $total" +
                        (if (result.failedImages.isNotEmpty())
                            "\n\n[실패 원인]\n" + result.failedImages.joinToString("\n") else "") +
                        (if (result.skipped.isNotEmpty())
                            "\n\n[건너뜀]\n" + result.skipped.joinToString("\n") else "")

                result.skipped.forEach { Log.d("BATCH", "건너뜀 - $it") }
                result.failedImages.forEach { Log.d("BATCH", "실패 - $it") }
                // 확인용: 저장된 문제 전체와 정답을 Logcat에 출력
                AppDatabase.getInstance(applicationContext).questionDao()
                    .getRandom(500).sortedBy { it.id }.forEach {
                        Log.d("DB", "id=${it.id} 정답=${it.correctAnswer} | ${it.questionText}")
                    }
            } finally {
                btnImport.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        batchImporter.close()
    }
}