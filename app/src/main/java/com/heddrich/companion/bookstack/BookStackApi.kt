package com.heddrich.companion.bookstack

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit-Definition der BookStack-REST-API.
 * Pitfalls aus dem Bestandsskill eingebaut:
 * - Chapters werden GLOBAL gelistet (/api/chapters mit filter[book_id]),
 *   nicht ueber /api/books/{id}/chapters (404).
 * - Attachments: Multipart-Feld heisst zwingend "file", Zielseite via "uploaded_to".
 */
interface BookStackApi {

    @GET("books")
    suspend fun books(
        @Query("count") count: Int = 50
    ): BookListResponse

    @GET("chapters")
    suspend fun chapters(
        @Query("count") count: Int = 100,
        @Query("filter[book_id]") bookId: Int
    ): ChapterListResponse

    @POST("chapters")
    suspend fun createChapter(
        @Body body: ChapterCreateRequest
    ): ChapterDto

    @GET("pages")
    suspend fun pages(
        @Query("count") count: Int = 10,
        @Query("filter[name]") nameEquals: String,
        @Query("filter[chapter_id]") chapterId: Int
    ): PageListResponse

    @POST("pages")
    suspend fun createPage(
        @Body body: PageWriteRequest
    ): PageDto

    @PUT("pages/{id}")
    suspend fun updatePage(
        @Path("id") id: Int,
        @Body body: PageWriteRequest
    ): PageDto

    /** Einzelne Seite abrufen (u. a. fuer die Vorlagen-Konfigurationsseite). */
    @GET("pages/{id}")
    suspend fun page(@Path("id") id: Int): PageDto

    @Multipart
    @POST("attachments")
    suspend fun createAttachment(
        @Part file: MultipartBody.Part,
        @Part("uploaded_to") uploadedTo: RequestBody,
        @Part("name") name: RequestBody
    ): AttachmentDto
}
