package com.metrolist.music.lyrics.spicy.api

import com.metrolist.music.lyrics.spicy.api.LyricsBody
import com.metrolist.music.lyrics.spicy.api.SyllableLine
import com.metrolist.music.betterlyrics.TTMLParser

object SpicyLyricsTtmlConverter {

    fun convertToParsedLines(body: LyricsBody): List<TTMLParser.ParsedLine> {
        val result = mutableListOf<TTMLParser.ParsedLine>()

        val defaultAgent = body.content.firstNotNullOfOrNull { it.agent } ?: "v1"

        for (contentLine in body.content) {
            val lead = contentLine.lead
            if (lead == null) {
                // Line-level fallback within syllable payload
                val lineText = contentLine.text?.trim().orEmpty()
                if (lineText.isNotEmpty()) {
                    val start = contentLine.startTime ?: 0.0
                    val end = contentLine.endTime ?: (start + 3.0)
                    val agent = if (contentLine.oppositeAligned) "v2" else (contentLine.agent ?: defaultAgent)
                    result.add(
                        TTMLParser.ParsedLine(
                            text = lineText,
                            startTime = start,
                            words = listOf(TTMLParser.ParsedWord(lineText, start, end)),
                            agent = agent,
                            isBackground = false
                        )
                    )
                }
                continue
            }

            // Syllable lead vocals
            val syllables = lead.syllables
            if (syllables.isEmpty()) continue

            val words = mutableListOf<TTMLParser.ParsedWord>()
            val lineTextBuilder = StringBuilder()

            var currentWordText = StringBuilder()
            var currentWordStart = 0.0
            var currentWordEnd = 0.0
            var isBuildingWord = false

            for (i in syllables.indices) {
                val syl = syllables[i]
                if (!isBuildingWord) {
                    currentWordText = StringBuilder(syl.text)
                    currentWordStart = syl.startTime
                    currentWordEnd = syl.endTime
                    isBuildingWord = true
                } else {
                    currentWordText.append(syl.text)
                    currentWordEnd = syl.endTime
                }

                if (!syl.isPartOfWord || i == syllables.lastIndex) {
                    val fullWord = currentWordText.toString().trim()
                    if (fullWord.isNotEmpty()) {
                        words.add(TTMLParser.ParsedWord(fullWord, currentWordStart, currentWordEnd))
                        if (lineTextBuilder.isNotEmpty()) lineTextBuilder.append(" ")
                        lineTextBuilder.append(fullWord)
                    }
                    isBuildingWord = false
                }
            }

            val lineText = lineTextBuilder.toString()
            val lineStart = lead.startTime.takeIf { it > 0 } ?: words.firstOrNull()?.startTime ?: 0.0

            val agent = if (contentLine.oppositeAligned) "v2" else (contentLine.agent ?: defaultAgent)

            // Background vocals
            val bgLines = mutableListOf<TTMLParser.ParsedLine>()
            for (bgGroup in contentLine.background) {
                val bgWords = mutableListOf<TTMLParser.ParsedWord>()
                val bgTextBuilder = StringBuilder()
                var currentBgWord = StringBuilder()
                var bgWordStart = 0.0
                var bgWordEnd = 0.0
                var isBuildingBg = false

                for (j in bgGroup.syllables.indices) {
                    val bgSyl = bgGroup.syllables[j]
                    if (!isBuildingBg) {
                        currentBgWord = StringBuilder(bgSyl.text)
                        bgWordStart = bgSyl.startTime
                        bgWordEnd = bgSyl.endTime
                        isBuildingBg = true
                    } else {
                        currentBgWord.append(bgSyl.text)
                        bgWordEnd = bgSyl.endTime
                    }

                    if (!bgSyl.isPartOfWord || j == bgGroup.syllables.lastIndex) {
                        val fullBgWord = currentBgWord.toString().trim()
                        if (fullBgWord.isNotEmpty()) {
                            bgWords.add(TTMLParser.ParsedWord(fullBgWord, bgWordStart, bgWordEnd))
                            if (bgTextBuilder.isNotEmpty()) bgTextBuilder.append(" ")
                            bgTextBuilder.append(fullBgWord)
                        }
                        isBuildingBg = false
                    }
                }

                val bgText = bgTextBuilder.toString()
                if (bgText.isNotEmpty()) {
                    bgLines.add(
                        TTMLParser.ParsedLine(
                            text = bgText,
                            startTime = bgGroup.startTime,
                            words = bgWords,
                            agent = agent,
                            isBackground = true
                        )
                    )
                }
            }

            result.add(
                TTMLParser.ParsedLine(
                    text = lineText,
                    startTime = lineStart,
                    words = words,
                    agent = agent,
                    isBackground = false,
                    backgroundLines = bgLines
                )
            )
        }

        return result
    }

    fun convertLineLyrics(body: LyricsBody): List<TTMLParser.ParsedLine> {
        return body.content.mapNotNull { line ->
            val text = line.text?.trim() ?: return@mapNotNull null
            val start = line.startTime ?: 0.0
            val end = line.endTime ?: (start + 3.0)
            val agent = if (line.oppositeAligned) "v2" else (line.agent ?: "v1")
            TTMLParser.ParsedLine(
                text = text,
                startTime = start,
                words = listOf(TTMLParser.ParsedWord(text, start, end)),
                agent = agent,
                isBackground = false
            )
        }
    }

    fun convertStaticLyrics(body: LyricsBody): String {
        return body.lines.joinToString("\n") { it.text }
    }
}
