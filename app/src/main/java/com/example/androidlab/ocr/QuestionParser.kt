package com.example.androidlab.ocr

import com.example.androidlab.data.Question

// 변환 결과: 만들어진 문제들 + 만들지 못하고 건너뛴 이유
data class ParseResult(
    val questions: List<Question>,
    val skipped: List<String>
)

// Part 2(질문 1개 + 선택지 A~C, 정답 선택지는 빨간 글자) 형식 전용 파서
object QuestionParser {

    private val numberLine = Regex("^\\d{1,3}$")                         // "11" 같은 문제 번호 줄
    private val speakerLabel = Regex("^[MW]-[A-Za-z]{2,3}\\b\\s*")       // "W-Br", "M-Au" 같은 화자 표시
    private val speakerOnly = Regex("^[MW]-[A-Za-z]{2,3}$")              // 화자 표시만 있는 줄
    private val choiceLine =
        Regex("^(?:[MW]-[A-Za-z]{2,3}\\s+)?\\(?\\s*([A-C])\\s*\\)\\s*(.+)$") // "(A) ..." (앞에 화자 표시가 있어도 됨)
    private val hangul = Regex("[가-힣]")
    private val junkLine = Regex("^[A-Za-z]{1,6}\\d{1,3}$")              // "IEST3" 같은 페이지 가장자리 잡음
    private val endsSentence = Regex("[.?!]$")
    private val answerInList = Regex("\\(\\s*([A-C])\\s*\\)\\s*정[답담단딥당]") // 해설 속 "(C) 정답." (OCR 오독 포함)
    private val choiceMarker = Regex("\\(\\s*([A-C])\\s*\\)")            // 해설 속 "(A)", "(B)" 표시
    private val wrongMark = Regex("오[답담단딥당]")                         // "오답" (OCR 오독 포함)

    // OCR로 읽은 줄들을 넣으면 Question 목록으로 바꿔준다.
    // redOf: 줄 번호를 주면 그 줄의 "빨간 글자 비율"을 알려주는 함수 (정답 선택지 판정용, 없으면 해설로만 판단)
    fun parse(
        lines: List<String>,
        category: String = "Part 2",
        imagePath: String = "",
        redOf: (Int) -> Float = { 0f }
    ): ParseResult {
        // 빈 줄을 뺀 목록. keep[i]는 cleaned[i]가 원래 몇 번째 줄이었는지
        val keep = lines.indices.filter { lines[it].trim().isNotEmpty() }
        val cleaned = keep.map { lines[it].trim() }

        // 숫자만 있는 줄(문제 번호)의 위치를 찾는다
        val starts = cleaned.indices.filter { numberLine.matches(cleaned[it]) }

        val questions = mutableListOf<Question>()
        val skipped = mutableListOf<String>()

        for ((n, start) in starts.withIndex()) {
            val end = if (n + 1 < starts.size) starts[n + 1] else cleaned.size
            val block = cleaned.subList(start + 1, end) // 이 문제에 속한 줄들만
            val result = parseBlock(block, category, imagePath) { i -> redOf(keep[start + 1 + i]) }

            val q = result.question
            if (q != null) {
                questions.add(q)
            } else {
                skipped.add("${cleaned[start]}번: ${result.error}")
            }
        }
        return ParseResult(questions, skipped)
    }

    private class BlockResult(val question: Question?, val error: String? = null)

    // 영어 글자 정리: OCR이 자주 틀리는 I 관련 오류를 고치고 공백을 정리한다
    private fun cleanEnglish(s: String): String =
        s.replace("|", " I ")                                   // 대문자 I가 세로선으로 읽힌 경우
            .replace(Regex("\\b(?:PI|II|I)l\\b"), "I'll")       // I'll 이 Il, PIl, IIl 로 읽힌 경우
            .replace(Regex("\\bl\\b"), "I")                     // 홀로 있는 소문자 l 은 I
            .replace(Regex("\\s+"), " ")
            .trim()

    // 해설에서 "오답"으로 표시된 선택지 두 개를 빼고 남은 하나를 정답으로 판단한다
    private fun answerByElimination(explanation: String): String? {
        val markers = choiceMarker.findAll(explanation).toList()
        val wrong = mutableSetOf<String>()
        for ((i, m) in markers.withIndex()) {
            val end = if (i + 1 < markers.size) markers[i + 1].range.first else explanation.length
            val segment = explanation.substring(m.range.last + 1, end)
            if (wrongMark.containsMatchIn(segment)) wrong.add(m.groupValues[1])
        }
        val rest = listOf("A", "B", "C") - wrong
        return if (wrong.size == 2 && rest.size == 1) rest[0] else null
    }

    // 문제 하나에 속한 줄들을 Question으로 만든다
    private fun parseBlock(
        rawBlock: List<String>,
        category: String,
        imagePath: String,
        redOfBlockLine: (Int) -> Float
    ): BlockResult {
        // 0) 해설 뒤에 다음 문제의 화자 표시(M-Au 등)가 나오면 거기서 자른다
        val expStart = rawBlock.indexOfFirst { it.startsWith("해설") }
        val block = if (expStart >= 0) {
            val cut = (expStart + 1 until rawBlock.size)
                .firstOrNull { speakerLabel.containsMatchIn(rawBlock[it]) }
            if (cut != null) rawBlock.subList(0, cut) else rawBlock
        } else rawBlock

        // 1) 영어 선택지: 한글이 없는 (A)~(C) 줄만, 각 알파벳의 첫 번째 것
        val choices = mutableMapOf<String, String>()
        val choiceLineIndex = mutableMapOf<String, Int>() // 선택지가 몇 번째 줄에 있었는지
        var firstChoiceIndex = -1
        for ((i, line) in block.withIndex()) {
            if (hangul.containsMatchIn(line)) continue
            val m = choiceLine.find(line) ?: continue
            val letter = m.groupValues[1]
            if (letter in choices) continue

            var text = m.groupValues[2].trim()

            // 선택지가 두 줄로 끊긴 경우("I need a" / "receipt for that.") 이어 붙인다
            val next = block.getOrNull(i + 1)
            if (!endsSentence.containsMatchIn(text) && next != null &&
                !hangul.containsMatchIn(next) &&
                !choiceLine.matches(next) &&
                !speakerLabel.containsMatchIn(next) &&
                !numberLine.matches(next) &&
                !junkLine.matches(next)
            ) {
                text = "$text $next"
            }

            choices[letter] = cleanEnglish(text)
            choiceLineIndex[letter] = i
            if (firstChoiceIndex == -1) firstChoiceIndex = i
        }
        if (choices.size < 3) return BlockResult(null, "선택지 A~C를 찾지 못함")

        // 2) 문제 문장: 첫 번째 선택지 위쪽 줄들 (화자 표시만 있는 줄과 앞의 화자 표시는 뺀다)
        val questionLines = block.subList(0, firstChoiceIndex).filterNot { speakerOnly.matches(it) }
        val questionText = cleanEnglish(
            questionLines.joinToString(" ").replaceFirst(speakerLabel, "")
        )
        if (questionText.isBlank()) return BlockResult(null, "문제 문장을 찾지 못함")

        // 3) 해설: "해설"로 시작하는 줄부터 끝까지 (해설이 잘린 사진이면 비어 있을 수 있다)
        val expIndex = block.indexOfFirst { it.startsWith("해설") }
        val explanation = if (expIndex >= 0) {
            block.drop(expIndex).joinToString(" ").removePrefix("해설").trim()
        } else ""

        // 4) 정답 ① 해설 속 "(C) 정답."을 찾고, 못 찾으면 오답 두 개를 빼고 남은 것
        val tailText = block.drop(firstChoiceIndex).joinToString(" ")
        val textAnswer = answerInList.findAll(tailText).lastOrNull()?.groupValues?.get(1)
            ?: answerByElimination(explanation)

        // 5) 정답 ② 영어 선택지 중 빨간 글자인 줄 (다른 줄보다 뚜렷하게 빨개야 인정)
        val ratios = choiceLineIndex.mapValues { redOfBlockLine(it.value) }
        val sorted = ratios.entries.sortedByDescending { it.value }
        val colorAnswer =
            if (sorted.size >= 2 &&
                sorted[0].value >= 0.12f &&
                sorted[0].value >= 3f * maxOf(sorted[1].value, 0.02f)
            ) sorted[0].key else null

        // 6) 두 방법을 합친다. 서로 다르면 틀린 정답을 저장하지 않도록 건너뛴다.
        val answer = when {
            colorAnswer != null && textAnswer != null && colorAnswer != textAnswer ->
                return BlockResult(null, "정답이 서로 다름 (글자색 $colorAnswer / 해설 $textAnswer)")
            colorAnswer != null -> colorAnswer
            textAnswer != null -> textAnswer
            else -> return BlockResult(null, "정답을 찾지 못함 (해설: ${explanation.take(300)})")
        }

        return BlockResult(
            Question(
                questionText = questionText,
                choiceA = choices.getValue("A"),
                choiceB = choices.getValue("B"),
                choiceC = choices.getValue("C"),
                choiceD = "", // Part 2는 선택지가 3개라서 D는 비워 둔다
                correctAnswer = answer,
                explanation = explanation,
                category = category,
                imagePath = imagePath
            )
        )
    }
}