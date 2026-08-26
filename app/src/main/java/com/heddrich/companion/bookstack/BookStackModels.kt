package com.heddrich.companion.bookstack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BookStack-API-DTOs. Die API liefert snake_case (z. B. "book_id"),
 * daher die SerialName-Annotationen. Unbekannte Felder werden ignoriert,
 * damit neue BookStack-Versionen den Client nicht brechen.
 */

@Serializable
data class BookDto(
    val id: Int,
    val name: String,
    val slug: String? = null
)

@Serializable
data class BookListResponse(
    val data: List<BookDto> = emptyList()
)

@Serializable
data class ChapterDto(
    val id: Int,
    val name: String,
    @SerialName("book_id") val bookId: Int = 0
)

@Serializable
data class ChapterListResponse(
    val data: List<ChapterDto> = emptyList()
)

@Serializable
data class ChapterCreateRequest(
    @SerialName("book_id") val bookId: Int,
    val name: String,
    val description: String = ""
)

@Serializable
data class PageDto(
    val id: Int,
    val name: String,
    val slug: String? = null,
    @SerialName("book_id") val bookId: Int? = null,
    @SerialName("chapter_id") val chapterId: Int? = null,
    val html: String? = null,
    /** Vollstaendige Wiki-URL laut BookStack – spart uns eigene URL-Bastelei. */
    val url: String? = null
)

@Serializable
data class PageListResponse(
    val data: List<PageDto> = emptyList()
)

@Serializable
data class TagDto(
    val name: String,
    val value: String
)

@Serializable
data class PageWriteRequest(
    @SerialName("chapter_id") val chapterId: Int? = null,
    @SerialName("book_id") val bookId: Int? = null,
    val name: String,
    val html: String,
    val tags: List<TagDto> = emptyList()
)

@Serializable
data class AttachmentDto(
    val id: Int,
    val name: String? = null,
    @SerialName("uploaded_to") val uploadedTo: Int? = null
)

@Serializable
data class AttachmentListResponse(
    val data: List<AttachmentDto> = emptyList()
)
