package com.example.androidlab.ocr

import com.example.androidlab.data.Question

// 변환 결과를 담는 상자.
// questions: 만들어진 문제들 / skipped: 만들지 못한 문제와 이유
data class ParseResult(
    val questions: List<Question>,
    val skipped: List<String>
)

/*
 * TOEIC Part 2 간단한 파서
 *
 * 흐름: OCR이 읽은 줄들 → 문제 번호로 나누기 → 문제 문장 + A/B/C 찾기 → Question 만들기
 *
 * object = 객체가 딱 하나만 있는 클래스. Java의 static 메서드 모음과 비슷하다.
 */
object QuestionParser {

    // ===== 글자 모양을 찾는 규칙 (정규식, Regex) =====

    // 문제 번호 줄: "19", "20.", "21)" 처럼 숫자만 있는 줄
    private val numberRegex = Regex("""^\s*\d{1,2}\s*[.)]?\s*$""")

    // 화자 표시: "M-Cn", "W-Br" 같은 것 (문제 문장에서 지운다)
    private val speakerRegex = Regex("""^[MW]-[A-Za-z]{2,3}\s*""")

    // 선택지 줄: "(A) 내용", "A) 내용", "A. 내용", "M-Au (A) 내용"
    // 첫 번째 그룹 = 알파벳(A/B/C), 두 번째 그룹 = 내용
    // 알파벳 뒤에 ")" 또는 "."이 꼭 있어야 한다. (없으면 "A very nice day" 같은 문장도 선택지로 착각한다)
    private val choiceRegex =
        Regex("""^\s*(?:[MW]-[A-Za-z]{2,3}\s+)?\(?([ABC])[).]\s*(.+)$""")

    // ===== 작은 도우미 함수들 =====

    // 이 줄이 문제 번호인가?
    private fun isQuestionNumber(text: String): Boolean = numberRegex.matches(text)

    // 이 줄이 선택지면 ("A" to "내용") 형태로, 아니면 null을 돌려준다.
    // 타입 뒤의 ? 는 "null일 수도 있다"는 뜻이다.
    private fun parseChoice(text: String): Pair<String, String>? {
        val match = choiceRegex.matchEntire(text.trim()) ?: return null // ?: 는 "왼쪽이 null이면 오른쪽 실행"
        return match.groupValues[1] to match.groupValues[2].trim()
    }

    // OCR 글자를 아주 조금만 정리한다 (세로선 | → I, 굽은 따옴표 → 보통 따옴표, 공백 정리)
    private fun cleanEnglish(text: String): String =
        text.replace("|", "I")
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()

    // ===== 1단계: 페이지 전체를 문제 단위로 나눈다 =====
    fun parse(lines: List<OcrLine>): ParseResult {
        // 문제 번호가 있는 줄의 위치(인덱스)를 모두 찾는다. 예: [0, 7, 14]
        val starts = lines.indices.filter { isQuestionNumber(lines[it].text) }
        if (starts.isEmpty()) {
            return ParseResult(emptyList(), listOf("문제 번호를 찾지 못했습니다."))
        }

        val questions = mutableListOf<Question>() // mutable = 나중에 add 할 수 있는 목록
        val skipped = mutableListOf<String>()

        for ((index, start) in starts.withIndex()) {
            // 이 문제의 끝 = 다음 문제 번호 바로 앞 (마지막 문제면 페이지 끝)
            val end = starts.getOrNull(index + 1) ?: lines.size

            // 번호 줄 다음부터 end 전까지가 이 문제의 줄들
            val question = parseBlock(lines.subList(start + 1, end))

            if (question != null) {
                questions.add(question)
            } else {
                skipped.add("${lines[start].text}번 문제를 인식하지 못했습니다.")
            }
        }
        return ParseResult(questions, skipped)
    }

    // ===== 2단계: 문제 하나(줄 묶음)를 Question으로 만든다 =====
    // 만들 수 없으면 null을 돌려준다.
    private fun parseBlock(lines: List<OcrLine>): Question? {
        // 선택지 내용을 담는 지도(Map). 예: "A" → "The bookstore is closed."
        val choices = mutableMapOf<String, String>()
        var firstChoice = -1 // 첫 번째 선택지가 몇 번째 줄인지 (-1 = 아직 못 찾음)

        for ((i, line) in lines.withIndex()) {
            val choice = parseChoice(line.text) ?: continue // 선택지가 아니면 다음 줄로
            if (choice.first in choices) continue            // 같은 알파벳이 또 나오면 첫 번째만 쓴다
            choices[choice.first] = cleanEnglish(choice.second)
            if (firstChoice == -1) firstChoice = i
        }

        // Part 2는 A, B, C가 모두 있어야 한다. 하나라도 없으면 포기(null).
        if ("A" !in choices || "B" !in choices || "C" !in choices) return null

        // 문제 문장 = 첫 번째 선택지 위쪽의 줄들을 이어붙인 것
        val questionText = lines.subList(0, firstChoice)
            .map { cleanEnglish(it.text.replaceFirst(speakerRegex, "")) } // 화자 표시 지우고 정리
            .filter { it.isNotBlank() }                                   // 빈 줄 버리기
            .joinToString(" ")                                            // 공백으로 이어붙이기
        if (questionText.isBlank()) return null

        // 정답은 비워 둔다 (""는 빈 문자열). 나중에 사람이 입력한다.
        return Question(
            questionText = questionText,
            choiceA = choices.getValue("A"),
            choiceB = choices.getValue("B"),
            choiceC = choices.getValue("C"),
            correctAnswer = ""
        )
    }
}