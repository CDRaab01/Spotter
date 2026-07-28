package com.spotter.data.export

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * The `Content-Disposition` half of the export download is the only server-controlled input on the
 * client side, so it is parsed by a pure function and pinned here.
 */
class ExportFilenamesTest {

    private val fallback = "spotter-export-2026-07-28.json"

    @Test
    fun `fallback name is prefix-date-extension`() {
        val date = LocalDate.parse("2026-07-28")
        assertEquals("spotter-export-2026-07-28.json", ExportFilenames.fallback(ExportKind.JSON, date))
        assertEquals("spotter-sets-2026-07-28.csv", ExportFilenames.fallback(ExportKind.CSV, date))
    }

    @Test
    fun `quoted filename is used`() {
        assertEquals(
            "spotter-export-2026-07-01.json",
            ExportFilenames.resolve(
                """attachment; filename="spotter-export-2026-07-01.json"""",
                fallback,
            ),
        )
    }

    @Test
    fun `unquoted filename is used`() {
        assertEquals(
            "spotter-sets-2026-07-01.csv",
            ExportFilenames.resolve("attachment; filename=spotter-sets-2026-07-01.csv", fallback),
        )
    }

    @Test
    fun `rfc 5987 extended filename wins and is percent-decoded`() {
        assertEquals(
            "spotter export.json",
            ExportFilenames.resolve(
                """attachment; filename="fallback.json"; filename*=UTF-8''spotter%20export.json""",
                fallback,
            ),
        )
    }

    @Test
    fun `missing header falls back`() {
        assertEquals(fallback, ExportFilenames.resolve(null, fallback))
    }

    @Test
    fun `header without a filename parameter falls back`() {
        assertEquals(fallback, ExportFilenames.resolve("attachment", fallback))
    }

    @Test
    fun `blank or empty filename falls back`() {
        assertEquals(fallback, ExportFilenames.resolve("""attachment; filename=""""", fallback))
        assertEquals(fallback, ExportFilenames.resolve("""attachment; filename="   """", fallback))
    }

    @Test
    fun `path segments are stripped so a hostile name cannot escape the export dir`() {
        assertEquals(
            "passwd",
            ExportFilenames.resolve("""attachment; filename="../../etc/passwd"""", fallback),
        )
        assertEquals(
            "evil.json",
            ExportFilenames.resolve("""attachment; filename="C:\windows\evil.json"""", fallback),
        )
        // Nothing but traversal left ⇒ fall back rather than write a dotted name.
        assertEquals(fallback, ExportFilenames.resolve("""attachment; filename=".."""", fallback))
    }

    @Test
    fun `parameter name matching is case-insensitive`() {
        assertEquals(
            "export.json",
            ExportFilenames.resolve("""ATTACHMENT; FILENAME="export.json"""", fallback),
        )
    }
}
