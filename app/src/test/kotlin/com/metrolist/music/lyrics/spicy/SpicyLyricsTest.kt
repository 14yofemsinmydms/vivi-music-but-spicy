package com.metrolist.music.lyrics.spicy

import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.music.lyrics.spicy.api.LyricsEnvelope
import com.metrolist.music.lyrics.spicy.api.SpicyLyricsTtmlConverter
import com.metrolist.music.lyrics.spicy.models.LyricsType
import com.metrolist.music.lyrics.spicy.parser.TtmlLyricsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpicyLyricsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testJsonDeserializationAndTtmlConversion() {
        val sampleJson = """
        {
          "Status": 200,
          "Type": "object",
          "Body": {
            "id": "4cOdK2wGLETKBW3PvgPWqT",
            "Type": "Syllable",
            "source": "spicy_lyrics",
            "UploadAttribution": {
              "Uploader": {
                "id": "u1",
                "username": "SpicyContributor",
                "url": "https://spicylyrics.org/u/spicycontributor"
              },
              "Maker": {
                "id": "u2",
                "username": "SpicyMaker",
                "url": "https://spicylyrics.org/u/spicymaker"
              }
            },
            "Content": [
              {
                "Type": "Vocal",
                "OppositeAligned": false,
                "Agent": "v1",
                "Lead": {
                  "StartTime": 12.34,
                  "EndTime": 15.67,
                  "Syllables": [
                    { "Text": "Nev", "IsPartOfWord": true, "StartTime": 12.34, "EndTime": 12.60 },
                    { "Text": "er ", "IsPartOfWord": false, "StartTime": 12.60, "EndTime": 13.00 },
                    { "Text": "gon", "IsPartOfWord": true, "StartTime": 13.00, "EndTime": 13.30 },
                    { "Text": "na ", "IsPartOfWord": false, "StartTime": 13.30, "EndTime": 13.60 },
                    { "Text": "give ", "IsPartOfWord": false, "StartTime": 13.60, "EndTime": 14.10 },
                    { "Text": "you ", "IsPartOfWord": false, "StartTime": 14.10, "EndTime": 14.50 },
                    { "Text": "up", "IsPartOfWord": false, "StartTime": 14.50, "EndTime": 15.67 }
                  ]
                },
                "Background": [
                  {
                    "StartTime": 13.60,
                    "EndTime": 15.67,
                    "Syllables": [
                      { "Text": "give ", "IsPartOfWord": false, "StartTime": 13.60, "EndTime": 14.10 },
                      { "Text": "you ", "IsPartOfWord": false, "StartTime": 14.10, "EndTime": 14.50 },
                      { "Text": "up", "IsPartOfWord": false, "StartTime": 14.50, "EndTime": 15.67 }
                    ]
                  }
                ]
              },
              {
                "Type": "Vocal",
                "OppositeAligned": true,
                "Lead": {
                  "StartTime": 16.00,
                  "EndTime": 18.50,
                  "Syllables": [
                    { "Text": "Nev", "IsPartOfWord": true, "StartTime": 16.00, "EndTime": 16.30 },
                    { "Text": "er ", "IsPartOfWord": false, "StartTime": 16.30, "EndTime": 16.70 },
                    { "Text": "let ", "IsPartOfWord": false, "StartTime": 16.70, "EndTime": 17.20 },
                    { "Text": "you ", "IsPartOfWord": false, "StartTime": 17.20, "EndTime": 17.60 },
                    { "Text": "down", "IsPartOfWord": false, "StartTime": 17.60, "EndTime": 18.50 }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()

        val envelope = json.decodeFromString<LyricsEnvelope>(sampleJson)
        val body = envelope.body
        assertNotNull(body)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", body?.id)
        assertEquals("Syllable", body?.type)
        assertEquals(2, body?.content?.size)

        val parsed = SpicyLyricsTtmlConverter.convertToParsedLines(body!!)
        assertEquals(2, parsed.size)

        // Line 1 assertions
        val line1 = parsed[0]
        assertEquals("Never gonna give you up", line1.text)
        assertEquals("v1", line1.agent)
        assertEquals(12.34, line1.startTime, 0.001)
        assertFalse(line1.isBackground)
        assertEquals(5, line1.words.size)
        assertEquals("Never", line1.words[0].text)
        assertEquals(12.34, line1.words[0].startTime, 0.001)
        assertEquals(13.00, line1.words[0].endTime, 0.001)
        assertEquals("gonna", line1.words[1].text)
        assertEquals(13.00, line1.words[1].startTime, 0.001)
        assertEquals(13.60, line1.words[1].endTime, 0.001)

        // Background line assertions
        assertEquals(1, line1.backgroundLines.size)
        val bgLine = line1.backgroundLines[0]
        assertEquals("give you up", bgLine.text)
        assertTrue(bgLine.isBackground)
        assertEquals(13.60, bgLine.startTime, 0.001)

        // Line 2 oppositeAligned (duet agent v2) assertions
        val line2 = parsed[1]
        assertEquals("Never let you down", line2.text)
        assertEquals("v2", line2.agent)
        assertEquals(16.00, line2.startTime, 0.001)
        assertEquals(4, line2.words.size)

        // Conversion to LRC serialization
        val lrc = TTMLParser.toLRC(parsed)
        assertTrue(lrc.contains("Never"))
        assertTrue(lrc.contains("gonna"))
        assertTrue(lrc.contains("down"))

        // Attribution extraction
        assertEquals("SpicyContributor", body.uploadAttribution?.uploader?.username)
        assertEquals("SpicyMaker", body.uploadAttribution?.maker?.username)
    }

    @Test
    fun testTtmlLyricsParserDirectXml() {
        val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            itunes:timing="Word">
            <head>
                <metadata>
                    <itunes:songwriters>Rick Astley</itunes:songwriters>
                </metadata>
            </head>
            <body>
                <div begin="00:01.000" end="00:10.000" itunes:songPart="Verse">
                    <p begin="00:01.000" end="00:04.500" ttm:agent="v1" itunes:key="L1">
                        <span begin="00:01.000" end="00:01.500">We\'re </span>
                        <span begin="00:01.500" end="00:02.000">no </span>
                        <span begin="00:02.000" end="00:02.800">stran</span><span begin="00:02.800" end="00:03.500">gers </span>
                        <span begin="00:03.500" end="00:04.000">to </span>
                        <span begin="00:04.000" end="00:04.500">love</span>
                    </p>
                </div>
            </body>
        </tt>
        """.trimIndent()

        val parsed = TtmlLyricsParser.parse(ttml.byteInputStream())
        assertNotNull(parsed)
        assertEquals(LyricsType.Syllable, parsed.type)
        assertEquals(1, parsed.lines.size)

        val line = parsed.lines[0]
        assertEquals(1000L, line.startMs)
        assertEquals(4500L, line.endMs)
        assertEquals("v1", line.agent)
        // Check syllable word binding ("stran" + "gers")
        val strangersSyl1 = line.words.find { it.text.contains("stran") }
        val strangersSyl2 = line.words.find { it.text.contains("gers") }
        assertNotNull(strangersSyl1)
        assertNotNull(strangersSyl2)
        assertFalse(strangersSyl1!!.isPartOfWord)
        assertTrue(strangersSyl2!!.isPartOfWord)
    }

    @Test
    fun testWordBoundaryPreservationInLineSyncedLyrics() {
        val lineText = "Hello world from Metrolist"
        val tokens = lineText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(4, tokens.size)
        assertEquals(listOf("Hello", "world", "from", "Metrolist"), tokens)

        var searchOffset = 0
        val continuesIntoNext = tokens.mapIndexed { idx, cleanWord ->
            val isLast = idx == tokens.size - 1
            val wordPos = lineText.indexOf(cleanWord, searchOffset)
            if (wordPos >= 0 && !isLast) {
                val endPos = wordPos + cleanWord.length
                searchOffset = endPos
                if (endPos < lineText.length) {
                    !lineText[endPos].isWhitespace()
                } else false
            } else false
        }
        val isPartOfWordList = tokens.mapIndexed { idx, _ ->
            idx > 0 && continuesIntoNext[idx - 1]
        }
        // Since all tokens have spaces after them, none should be marked isPartOfWord
        assertFalse(isPartOfWordList.any { it })
    }
}
