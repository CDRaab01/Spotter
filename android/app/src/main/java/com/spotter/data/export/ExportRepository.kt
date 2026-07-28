package com.spotter.data.export

import android.content.Context
import com.spotter.data.remote.ApiService
import com.spotter.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What the user asked to export — picks the endpoint, the fallback filename, and the share MIME. */
enum class ExportKind(val fallbackPrefix: String, val extension: String, val mimeType: String) {
    /** The full JSON export (`GET /export`). */
    JSON("spotter-export", "json", "application/json"),

    /** Every logged set as CSV (`GET /export/sets.csv`). */
    CSV("spotter-sets", "csv", "text/csv"),
}

/** A finished export: the file on disk plus the MIME type to share it as. */
data class ExportedFile(val file: File, val mimeType: String)

/**
 * Downloads the server's data exports into app-scoped cache storage so Settings can hand them to
 * the Android share sheet (via the `${applicationId}.fileprovider` FileProvider).
 *
 * Deliberately **not** offline-capable: an export is a point-in-time snapshot of the server's copy,
 * so with no connection it fails loudly (an [IOException] the caller turns into "check your
 * connection") rather than exporting a partial local mirror.
 */
@Singleton
class ExportRepository @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** The full JSON export. */
    suspend fun exportJson(): ExportedFile = export(ExportKind.JSON) { api.exportJson() }

    /** Every logged set as CSV. */
    suspend fun exportCsv(): ExportedFile = export(ExportKind.CSV) { api.exportSetsCsv() }

    private suspend fun export(
        kind: ExportKind,
        fetch: suspend () -> Response<ResponseBody>,
    ): ExportedFile = withContext(ioDispatcher) {
        val response = fetch()
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw IOException("Export failed (HTTP ${response.code()})")
        }
        val name = ExportFilenames.resolve(
            contentDisposition = response.headers()["Content-Disposition"],
            fallback = ExportFilenames.fallback(kind, LocalDate.now()),
        )
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, name)
        body.byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        ExportedFile(file = file, mimeType = kind.mimeType)
    }

    companion object {
        /** Cache subdirectory; mirrored by the `exports/` cache-path in `res/xml/file_paths.xml`. */
        const val EXPORT_DIR = "exports"
    }
}

/**
 * Filename resolution for a download, kept pure so the `Content-Disposition` parsing (the fiddly,
 * server-controlled part) is unit-testable without a network or a filesystem.
 */
object ExportFilenames {

    /** `spotter-export-2026-07-28.json` / `spotter-sets-2026-07-28.csv`. */
    fun fallback(kind: ExportKind, date: LocalDate): String =
        "${kind.fallbackPrefix}-$date.${kind.extension}"

    /**
     * The filename to save as: the server's suggestion from [contentDisposition] when it offers a
     * usable one, else [fallback].
     *
     * Handles `filename="quoted"`, bare `filename=value`, and RFC 5987 `filename*=UTF-8''percent%20encoded`
     * (preferred when both are present). Anything the server sends is treated as hostile: path
     * separators and traversal segments are stripped, so a malicious header can never write outside
     * the export directory.
     */
    fun resolve(contentDisposition: String?, fallback: String): String {
        val suggested = contentDisposition?.let { parse(it) }
        return sanitize(suggested) ?: fallback
    }

    private fun parse(header: String): String? =
        extended(header) ?: plain(header)

    /** `filename*=UTF-8''spotter%2Dexport.json` (charset'language'percent-encoded-value). */
    private fun extended(header: String): String? {
        val raw = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim() ?: return null
        // Drop the charset'language' prefix when present, then percent-decode.
        val value = raw.substringAfterLast('\'', raw)
        return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }

    /** `filename="spotter-export.json"` or `filename=spotter-export.json`. */
    private fun plain(header: String): String? =
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
            ?: Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.trim()

    /** Reduces a server-supplied name to a bare, safe filename — or null when nothing is left. */
    private fun sanitize(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val base = name.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        return base
    }
}
