package com.metrolist.music.lyrics.spicy.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LyricsEnvelope(
    @SerialName("Body") val body: LyricsBody? = null,
    @SerialName("Status") val status: Int = 200,
    @SerialName("Type") val type: String = "object",
)

@Serializable
data class LyricsBody(
    @SerialName("Type") val type: String = "",
    @SerialName("id") val id: String = "",
    @SerialName("source") val source: String = "",
    @SerialName("SongWriters") val songWriters: List<String> = emptyList(),
    @SerialName("StartTime") val startTime: Double = 0.0,
    @SerialName("EndTime") val endTime: Double = 0.0,
    @SerialName("UploadAttribution") val uploadAttribution: UploadAttribution? = null,
    @SerialName("Content") val content: List<SyllableLine> = emptyList(),
    @SerialName("Lines") val lines: List<StaticLine> = emptyList(),
)

@Serializable
data class UploadAttribution(
    @SerialName("Uploader") val uploader: Contributor? = null,
    @SerialName("Maker") val maker: Contributor? = null,
)

@Serializable
data class Contributor(
    @SerialName("id") val id: String = "",
    @SerialName("username") val username: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("avatar") val avatar: String? = null,
    @SerialName("hasProfileBanner") val hasProfileBanner: Boolean = false,
)

@Serializable
data class SyllableLine(
    @SerialName("Type") val type: String = "Vocal",
    @SerialName("OppositeAligned") val oppositeAligned: Boolean = false,
    @SerialName("Agent") val agent: String? = null,
    @SerialName("Lead") val lead: VocalGroup? = null,
    @SerialName("Background") val background: List<VocalGroup> = emptyList(),
    @SerialName("Text") val text: String? = null,
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
)

@Serializable
data class VocalGroup(
    @SerialName("StartTime") val startTime: Double = 0.0,
    @SerialName("EndTime") val endTime: Double = 0.0,
    @SerialName("Syllables") val syllables: List<Syllable> = emptyList(),
    @SerialName("Text") val text: String? = null,
)

@Serializable
data class Syllable(
    @SerialName("Text") val text: String = "",
    @SerialName("StartTime") val startTime: Double = 0.0,
    @SerialName("EndTime") val endTime: Double = 0.0,
    @SerialName("IsPartOfWord") val isPartOfWord: Boolean = false,
    @SerialName("TransliteratedText") val transliteratedText: String? = null,
)

@Serializable
data class StaticLine(
    @SerialName("Text") val text: String = "",
)

@Serializable
data class ErrorEnvelope(
    @SerialName("Body") val body: ErrorBody? = null,
    @SerialName("Status") val status: Int = 400,
)

@Serializable
data class ErrorBody(
    @SerialName("error") val error: String = "",
    @SerialName("message") val message: String = "",
)
