package com.example.androidlab.data

// 해설이 저장된 정답을 뒷받침하는지
enum class AnswerEvidence { MATCH, UNVERIFIED, CONFLICT }

data class QuestionCheckResult(
    val evidence: AnswerEvidence,
    val explanationAnswer: String?,     // 해설에서 다시 읽어낸 정답 (못 읽으면 null)
    val typoWarnings: List<String>      // 오타 의심 목록
)

// 저장된 문제를 "다시 읽어서" 의심스러운 곳을 찾아내는 확인용 도구. DB는 건드리지 않는다.
object QuestionCheck {

    private val answerInList = Regex("\\(\\s*([A-C])\\s*\\)\\s*정[답담단딥당]")
    private val choiceMarker = Regex("\\(\\s*([A-C])\\s*\\)")
    private val wrongMark = Regex("오[답담단딥당]")

    private val hangul = Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]")
    private val latin = Regex("[A-Za-z]")
    private val digit = Regex("\\d")
    private val innerUpper = Regex("[a-z][A-Z]")                         // 소문자 바로 뒤의 대문자 (sOon)
    private val oddSymbols = Regex("[|~_{}\\[\\]<>\\\\^`@#*=]")           // 영어 문장에 나올 일이 거의 없는 기호
    private val ordinalOrTime =
        Regex("^\\d+(st|nd|rd|th|am|pm)$", RegexOption.IGNORE_CASE)     // 3rd, 10am 같은 정상 단어
    private val allowedSingle = setOf("I", "a", "A")                     // 정상적인 한 글자 단어

    // 문제 하나를 검사한다
    fun check(q: Question): QuestionCheckResult {
        val fromExplanation = answerInList.findAll(q.explanation).lastOrNull()?.groupValues?.get(1)
            ?: answerByElimination(q.explanation)

        val evidence = when {
            fromExplanation == null -> AnswerEvidence.UNVERIFIED
            fromExplanation.equals(q.correctAnswer, ignoreCase = true) -> AnswerEvidence.MATCH
            else -> AnswerEvidence.CONFLICT
        }

        val warnings = mutableListOf<String>()
        warnings += scan("문제", q.questionText)
        warnings += scan("A", q.choiceA)
        warnings += scan("B", q.choiceB)
        warnings += scan("C", q.choiceC)
        if (q.choiceD.isNotBlank()) warnings += scan("D", q.choiceD)

        return QuestionCheckResult(evidence, fromExplanation, warnings)
    }

    // 해설에서 "오답" 두 개를 빼고 남은 하나를 정답으로 본다 (QuestionParser와 같은 방식)
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

    // 영어 글 하나에서 오타 의심을 찾는다
    private fun scan(label: String, text: String): List<String> {
        val out = mutableListOf<String>()

        val symbols = oddSymbols.findAll(text).map { it.value }.distinct().toList()
        if (symbols.isNotEmpty()) out.add("$label: 이상한 기호 ${symbols.joinToString(" ")}")

        for (token in text.split(Regex("\\s+"))) {
            val w = token.trim { !it.isLetterOrDigit() } // 앞뒤 문장부호는 떼고 본다
            if (w.isEmpty()) continue

            val reasons = mutableListOf<String>()
            val hasHangul = hangul.containsMatchIn(w)
            if (hasHangul) reasons.add("한글 섞임")
            if (latin.containsMatchIn(w) && digit.containsMatchIn(w) && !ordinalOrTime.matches(w)) {
                reasons.add("숫자 섞임")
            }
            if (innerUpper.containsMatchIn(w)) reasons.add("대문자 섞임")
            if (!hasHangul && w.length == 1 && w[0].isLetter() && w !in allowedSingle) {
                reasons.add("한 글자 단어")
            }
            if (reasons.isNotEmpty()) out.add("$label: '$w' (${reasons.joinToString(", ")})")
        }
        return out
    }

    // 확인 화면에 보여줄 글. 첫 번째 항목은 요약 한 줄, 그 뒤는 문제별 한 덩어리씩.
    fun buildReport(questions: List<Question>): List<String> {
        val results = questions.map { it to check(it) }
        val match = results.count { it.second.evidence == AnswerEvidence.MATCH }
        val unverified = results.count { it.second.evidence == AnswerEvidence.UNVERIFIED }
        val conflict = results.count { it.second.evidence == AnswerEvidence.CONFLICT }
        val typo = results.count { it.second.typoWarnings.isNotEmpty() }

        val report = mutableListOf<String>()
        report.add("전체 ${questions.size}개: ✔ $match / ⚠ $unverified / ✖ $conflict, 오타 의심 ${typo}개")

        for ((q, r) in results) {
            val mark = when (r.evidence) {
                AnswerEvidence.MATCH -> "✔ 해설과 정답 일치"
                AnswerEvidence.UNVERIFIED -> "⚠ 해설로 확인 안 됨 (책과 꼭 대조)"
                AnswerEvidence.CONFLICT -> "✖ 해설은 ${r.explanationAnswer}를 가리킴 (정답이 틀렸을 가능성)"
            }
            val sb = StringBuilder()
            sb.append("[${q.id}] $mark\n")
            sb.append("질문 ${q.questionSpeaker.ifEmpty { "?" }} / 응답 ${q.answerSpeaker.ifEmpty { "?" }}\n")
            sb.append("Q: ${q.questionText}\n")
            sb.append("A. ${q.choiceA}\nB. ${q.choiceB}\nC. ${q.choiceC}")
            if (q.choiceD.isNotBlank()) sb.append("\nD. ${q.choiceD}")
            sb.append("\n저장된 정답: ${q.correctAnswer}")
            if (r.typoWarnings.isNotEmpty()) {
                sb.append("\n⚠ 오타 의심: ").append(r.typoWarnings.joinToString(" / "))
            }
            report.add(sb.toString())
        }
        return report
    }
}