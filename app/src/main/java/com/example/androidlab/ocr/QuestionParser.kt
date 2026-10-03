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
    private val speakerAtStart = Regex("^([MW]-[A-Za-z]{2,3})\\b")       // 줄 맨 앞의 화자 표시 (누가 말하는지 확인용)
    private val choiceLine =
        Regex("^(?:[MW]-[A-Za-z]{2,3}\\s+)?\\(?\\s*([A-C])\\s*\\)\\s*(.+)$") // "(A) ..." (앞에 화자 표시가 있어도 됨)
    private val hangul = Regex("[가-힣]")
    private val junkLine = Regex("^[A-Za-z]{1,6}\\d{1,3}$")              // "IEST3" 같은 페이지 가장자리 잡음
    private val endsSentence = Regex("[.?!]$")
    private val answerInList = Regex("\\(\\s*([A-C])\\s*\\)\\s*정[답담단딥당]") // 해설 속 "(C) 정답." (OCR 오독 포함)
    private val choiceMarker = Regex("\\(\\s*([A-C])\\s*\\)")            // 해설 속 "(A)", "(B)" 표시
    private val wrongMark = Regex("오[답담단딥당]")                         // "오답" (OCR 오독 포함)

    // ===== 영어 글자 교정 규칙 (OCR이 자주 틀리는 것들) =====
    private val zeroAfterLetter = Regex("(?<=[A-Za-z])0(?![0-9])")       // 글자 바로 뒤의 0 → o ("t0" → "to"). 숫자끼리 붙은 0은 제외
    private val zeroBeforeLetter = Regex("(?<![0-9])0(?=[A-Za-z])")      // 글자 바로 앞의 0 → o ("0n" → "on"). "10am"은 제외
    private val loneT = Regex("(?<![\\w-])T(?![\\w-])")                  // 홀로 있는 T (I가 T로 읽힌 경우). "T-shirt"는 제외
    private val spaceInContraction = Regex("(?<=[A-Za-z])'\\s+(ve|ll|re|m|d|s|t)\\b") // "we' ve" → "we've"
    private val extraSAfterApostropheS = Regex("(\\w+'s)\\s+s\\b")       // "It's s still" → "It's still"
    private val iPrefixedLl = Regex("(?<=[A-Za-z])'I\\b")                // "I'I see" → "I'll see"
    private val lostI_m = Regex("(?<![\\w'])'m\\b")                      // "'m staying" → "I'm staying"
    private val lostI_ll = Regex("(?<![\\w'])'ll\\b")                    // "'ll go" → "I'll go"
    private val ihAsIll = Regex("\\b[ITl]H\\b")                          // "IH turn" → "I'll turn"
    private val stuckI = Regex(                                          // "Thave", "Ineed", "lalways", "Tam" → "I have", "I am" ...
        "\\b[ITli](am|have|need|always|just|measured|will|would|think|should|could|was|like|want|got|saw|know|usually|already|actually|can|did|had|don't|didn't|haven't)\\b"
    )
    private val missingApostrophe = Regex(                               // "didnt" → "didn't"
        "\\b(did|was|is|does|could|would|should|have|has|are|do)nt\\b", RegexOption.IGNORE_CASE
    )
    private val missingApostropheLl = Regex(                             // "theyll" → "they'll" (he/she/we는 hell/shell/well이라 제외)
        "\\b(they|you|it|that|there|who|what)ll\\b", RegexOption.IGNORE_CASE
    )
    private val wordRun = Regex("[A-Za-z]+")
    private val innerUpper = Regex("[a-z][A-Z]")                         // 소문자 바로 뒤의 대문자 (HoW, hourS)
    private val twoCapsThenLower = Regex("^[A-Z]{2}[a-z]{2,}$")          // LOcally 같은 모양
    private val trailingJunk = Regex("[,;:\\-–]+$")                      // 문장 끝의 쉼표/하이픈 (마침표가 잘못 읽힌 경우)

    // 영어 단어가 아닌 "OCR 오독 철자" → 올바른 단어. (정상 영어 단어와 겹치는 것은 넣지 않았다)
    private val wordFixes: Map<String, String> = mapOf(
        // 글자가 빠진 경우
        "wll" to "will", "wil" to "will", "stil" to "still", "numbe" to "number",
        // m 이 rn 으로 읽힌 경우
        "tearn" to "team", "sorne" to "some", "sornething" to "something", "sorneone" to "someone",
        "rnore" to "more", "rnake" to "make", "rnuch" to "much", "rnay" to "may", "rnaybe" to "maybe",
        "rnust" to "must", "rnight" to "might", "rny" to "my", "rnail" to "mail", "rnap" to "map",
        "rnenu" to "menu", "rnoney" to "money", "rnonth" to "month", "rnorning" to "morning",
        "rneeting" to "meeting", "rnanager" to "manager", "rnanagement" to "management",
        "managernent" to "management", "departrnent" to "department", "cornpany" to "company",
        "cornputer" to "computer", "cornplete" to "complete", "tirne" to "time", "narne" to "name",
        "sarne" to "same", "forrn" to "form", "frorn" to "from", "ernail" to "email",
        "ernployee" to "employee", "ernployees" to "employees", "irnportant" to "important",
        "remnember" to "remember", "rernember" to "remember",
        // y 가 v 로 읽힌 경우
        "vou" to "you", "vour" to "your", "vours" to "yours", "vear" to "year", "vears" to "years",
        "vesterday" to "yesterday", "todav" to "today", "alwavs" to "always", "anvone" to "anyone",
        "anvthing" to "anything", "evervone" to "everyone", "evervthing" to "everything",
        "emplovee" to "employee", "emplovees" to "employees", "emplover" to "employer",
        "mav" to "may", "stav" to "stay", "pav" to "pay", "dav" to "day", "davs" to "days",
        // 그 밖에 자주 보이는 혼동
        "haif" to "half", "oroiect" to "project", "oroject" to "project"
    )

    // OCR로 읽은 줄들을 넣으면 Question 목록으로 바꿔준다.
    // redOf: 줄 번호를 주면 그 줄의 "빨간 글자 비율"을 알려주는 함수 (정답 선택지 판정용, 없으면 해설로만 판단)
    fun parse(
        lines: List<String>,
        category: String = "Part 2",
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
            val result = parseBlock(block, category) { i -> redOf(keep[start + 1 + i]) }

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

    // "W-br" 같은 표기를 "W-Br" 로 통일한다
    private fun normalizeSpeaker(s: String): String =
        "${s[0].uppercaseChar()}-${s[2].uppercaseChar()}${s.substring(3).lowercase()}"

    // 대소문자가 뒤섞인 단어를 고친다: HoW → How, hourS → hours, LOcally → Locally
    private fun fixMixedCase(s: String): String = wordRun.replace(s) { m ->
        val w = m.value
        if (w.length >= 3 && (innerUpper.containsMatchIn(w) || twoCapsThenLower.matches(w))) {
            w[0] + w.substring(1).lowercase()
        } else w
    }

    // 등록해 둔 오독 철자를 올바른 단어로 바꾼다 (첫 글자가 대문자였으면 대문자로 유지)
    private fun fixKnownMisreads(s: String): String = wordRun.replace(s) { m ->
        val w = m.value
        val fix = wordFixes[w.lowercase()]
        when {
            fix == null -> w
            w[0].isUpperCase() -> fix.replaceFirstChar { it.uppercaseChar() }
            else -> fix
        }
    }

    // 영어 글자 정리: OCR이 자주 틀리는 글자를 고치고 공백을 정리한다.
    // 순서가 중요하다 (앞 규칙의 결과를 뒤 규칙이 이어받는 경우가 있다).
    private fun cleanEnglish(s: String): String {
        var t = s
            .replace("|", " I ")                                // 대문자 I가 세로선으로 읽힌 경우
            .replace('’', '\'')                                 // 굽은 아포스트로피를 보통 것으로
            .replace(zeroAfterLetter, "o")                      // t0 → to, g0 → go (숫자끼리 붙은 0은 그대로)
            .replace(zeroBeforeLetter, "o")                     // 0n → on
            .replace(Regex("\\b(?:PI|II|I)l\\b"), "I'll")       // I'll 이 Il, PIl, IIl 로 읽힌 경우
            .replace(Regex("\\bl\\b"), "I")                     // 홀로 있는 소문자 l 은 I
            .replace(loneT, "I")                                // 홀로 있는 T 는 I (T brought → I brought)

        // 아포스트로피 주변
        t = t.replace(spaceInContraction, "'\$1")               // we' ve → we've
            .replace(extraSAfterApostropheS, "\$1")             // It's s still → It's still
            .replace("'I'll", "'ll")                            // I'I'll → I'll, We'I'll → We'll
            .replace(iPrefixedLl, "'ll")                        // I'I see → I'll see
            .replace(lostI_m, "I'm")                            // 'm staying → I'm staying
            .replace(lostI_ll, "I'll")
            .replace(ihAsIll, "I'll")                           // IH turn → I'll turn

        // 대문자 I가 옆 글자에 붙거나 다른 글자로 읽힌 경우
        t = t.replace(stuckI, "I \$1")                          // Thave → I have, Tam → I am, ijust → I just
            .replace(Regex("\\blt\\b"), "It")                   // lt's → It's
            .replace(Regex("\\bls\\b"), "Is")                   // ls this → Is this
            .replace(Regex("(?<![\\w'])sn't\\b"), "Isn't")      // sn't → Isn't
            .replace(Regex("\\bIm\\b"), "I'm")

        // 아포스트로피가 빠진 경우
        t = t.replace(missingApostrophe) { it.groupValues[1] + "n't" }   // didnt → didn't
            .replace(missingApostropheLl) { it.groupValues[1] + "'ll" }  // theyll → they'll

        // 끝에 붙은 잡글자, 대소문자 뒤섞임, 등록된 오독 철자
        t = t.replace(Regex("\\b(new)[Ww]\\b"), "\$1")          // newW → new (끝에 W가 따라붙은 경우)
        t = fixMixedCase(t)
        t = fixKnownMisreads(t)

        return t.replace(Regex("\\s+"), " ")
            .trim()
            .replace(trailingJunk, ".")                         // "supplier," → "supplier."
    }

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

        // 7) 말하는 사람: 선택지 위쪽 줄과 첫 선택지 줄 맨 앞의 화자 표시를 순서대로 모은다
        //    첫 번째 = 질문하는 사람, 두 번째 = 선택지(응답)를 말하는 사람
        val speakers = (0..firstChoiceIndex).mapNotNull { i ->
            speakerAtStart.find(block[i])?.groupValues?.get(1)?.let { normalizeSpeaker(it) }
        }

        // imagePath는 비워 둔다. QuestionImporter가 저장 직전에 채운다.
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
                questionSpeaker = speakers.getOrElse(0) { "" },
                answerSpeaker = speakers.getOrElse(1) { "" }
            )
        )
    }
}