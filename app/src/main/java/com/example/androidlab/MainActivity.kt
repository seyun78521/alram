package com.example.androidlab

import android.net.Uri
import android.os.Bundle
import android.speech.tts.Voice
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.androidlab.data.AppDatabase
import com.example.androidlab.data.LocalQuestionProvider
import com.example.androidlab.ocr.BatchImporter
import com.example.androidlab.tts.QuestionReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var batchImporter: BatchImporter
    private lateinit var reader: QuestionReader
    private lateinit var btnImport: Button
    private lateinit var btnSpeak: Button
    private lateinit var btnVoices: Button
    private lateinit var btnDemo: Button
    private lateinit var btnAudition: Button
    private lateinit var btnSetup: Button
    private lateinit var tvStatus: TextView
    private var speakJob: Job? = null

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
        reader = QuestionReader(applicationContext)
        btnImport = findViewById(R.id.btnImport)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnVoices = findViewById(R.id.btnVoices)
        btnDemo = findViewById(R.id.btnDemo)
        btnAudition = findViewById(R.id.btnAudition)
        btnSetup = findViewById(R.id.btnSetup)
        tvStatus = findViewById(R.id.tvStatus)

        btnImport.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        btnSpeak.setOnClickListener { speakRandom() }
        btnVoices.setOnClickListener {
            lifecycleScope.launch {
                val lines = reader.voiceListLines()
                lines.forEach { Log.d("VOICES", it) }
                tvStatus.text = "영어 음성 정보 ${lines.size}줄을 Logcat(tag:VOICES)에 출력했습니다"
            }
        }
        btnDemo.setOnClickListener {
            speakJob?.cancel()
            speakJob = lifecycleScope.launch {
                tvStatus.text = "W-Am → W-Br → M-Au → M-Cn 순서로 말합니다..."
                val ok = reader.demo()
                tvStatus.text = if (ok) "테스트 완료" else "테스트 실패 (소리 / TTS 설정 확인)"
            }
        }
        btnAudition.setOnClickListener {
            speakJob?.cancel()
            speakJob = lifecycleScope.launch {
                val lines = mutableListOf<String>()
                val ok = reader.audition { i, total, description ->
                    lines.add("$i. $description")
                    tvStatus.text = "듣는 중 ($i/$total)\n" + lines.joinToString("\n")
                }
                tvStatus.text = (if (ok) "끝\n" else "음성이 없거나 실패\n") + lines.joinToString("\n")
            }
        }
        btnSetup.setOnClickListener { openVoiceSetup() }
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
            } finally {
                btnImport.isEnabled = true
            }
        }
    }

    // DB에서 문제 1개를 랜덤으로 꺼내 화자별 목소리로 읽어준다
    private fun speakRandom() {
        speakJob?.cancel() // 읽는 중이면 멈추고 새로 시작
        speakJob = lifecycleScope.launch {
            val q = LocalQuestionProvider(applicationContext).getQuestions(1).firstOrNull()
            if (q == null) {
                tvStatus.text = "저장된 문제가 없습니다. 먼저 사진을 가져오세요."
                return@launch
            }
            val setupHint =
                if (reader.needsVoiceSetup()) "※ 이 폰에서는 일부 화자의 음성을 못 찾았습니다. '이 폰의 화자별 음성 설정'을 해 주세요.\n\n" else ""
            val info = "질문: ${q.questionSpeaker.ifEmpty { "?" }} / 응답: ${q.answerSpeaker.ifEmpty { "?" }}\n" +
                    "${q.questionText}\nA. ${q.choiceA}\nB. ${q.choiceB}\nC. ${q.choiceC}\n정답: ${q.correctAnswer}"
            Log.d("TTS", info)

            tvStatus.text = "${setupHint}읽는 중...\n$info"
            val ok = reader.read(q)
            tvStatus.text = setupHint + (if (ok) "읽기 완료\n" else "읽기 실패 (소리 / TTS 설정 확인)\n") + info
        }
    }

    // 이 폰에 설치된 영어 음성을 들어 보며 화자별로 고른다 (선택은 이 폰에 저장된다)
    private fun openVoiceSetup() {
        speakJob?.cancel()
        lifecycleScope.launch {
            val voices = reader.installedEnglishVoices()
            if (voices.isEmpty()) {
                tvStatus.text = "설치된 영어 음성이 없습니다. 설치 화면을 엽니다."
                reader.requestVoiceInstall()
                return@launch
            }
            chooseVoice(listOf("W-Am", "W-Br", "M-Au", "M-Cn"), 0, voices)
        }
    }

    private fun chooseVoice(labels: List<String>, index: Int, voices: List<Voice>) {
        if (index >= labels.size) {
            tvStatus.text = "음성 설정을 마쳤습니다. '화자별 목소리 테스트'로 확인해 보세요."
            return
        }
        val label = labels[index]
        val names = voices.map { "${it.name} (${it.locale})" }.toTypedArray()
        var selected = voices.indexOfFirst { it.name == reader.savedVoiceName(label) }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("$label 음성 선택 (${index + 1}/${labels.size})\n누르면 미리 들려줍니다")
            .setSingleChoiceItems(names, selected) { _, which ->
                selected = which
                speakJob?.cancel()
                speakJob = lifecycleScope.launch { reader.preview(voices[which], label) }
            }
            .setPositiveButton("저장하고 다음") { _, _ ->
                speakJob?.cancel()
                reader.saveVoice(label, voices[selected].name)
                chooseVoice(labels, index + 1, voices)
            }
            .setNegativeButton("건너뛰기") { _, _ ->
                speakJob?.cancel()
                chooseVoice(labels, index + 1, voices)
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        batchImporter.close()
        reader.shutdown()
    }
}