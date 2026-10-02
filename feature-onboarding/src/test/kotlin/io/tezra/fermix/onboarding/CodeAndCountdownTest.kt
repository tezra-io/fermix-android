package io.tezra.fermix.onboarding

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The vendored Noise vectors, on the test classpath from contracts/mobile. */
private const val VECTORS = "/noise_vectors.json"

/** Verify's code and countdown, and the bold name of "Shown as". */
class CodeAndCountdownTest {
    @Test
    fun `the previews' code is the vendored IKpsk2 vector's SAS`() {
        val stream = checkNotNull(javaClass.getResourceAsStream(VECTORS)) { "contracts/mobile is a test resource" }
        val vectors = Json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject
        val all = vectors.getValue("vectors").jsonArray.map { it.jsonObject }
        val pairing = all.single { it.text("pattern") == "ikpsk2" }
        assertEquals(pairing.text("sas"), PREVIEW_SAS)
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    @Test
    fun `the code shows in two groups of three`() {
        assertEquals("669 979", sasGroups("669979"))
        assertEquals("000 042", sasGroups("000042"))
        assertThrows<IllegalArgumentException> { sasGroups("66997") }
        assertThrows<IllegalArgumentException> { sasGroups("66997x") }
    }

    @Test
    fun `TalkBack reads the code digit by digit, a pause between the groups`() {
        assertEquals("6 6 9, 9 7 9", sasSpoken("669979"))
        assertEquals("0 0 0, 0 4 2", sasSpoken("000042"))
        assertThrows<IllegalArgumentException> { sasSpoken("66997") }
    }

    @Test
    fun `the clock reads m ss`() {
        assertEquals("2:00", clock(120))
        assertEquals("1:42", clock(102))
        assertEquals("0:09", clock(9))
        assertEquals("0:00", clock(0))
        assertThrows<IllegalArgumentException> { clock(-1) }
    }

    @Test
    fun `the seconds left round up, reach 0 00 as the window closes, and tick as the second turns`() {
        val time = TestTimeSource()
        val expiresAt = time.markNow() + 120.seconds
        assertEquals(120, secondsUntil(expiresAt))
        time += 18.seconds + 1.milliseconds
        assertEquals(102, secondsUntil(expiresAt))
        assertEquals(999L, millisToNextSecond(expiresAt))
        time += 999.milliseconds
        assertEquals(1_000L, millisToNextSecond(expiresAt))
        time += 102.seconds
        assertEquals(0, secondsUntil(expiresAt))
        time += 5.seconds
        assertEquals(0, secondsUntil(expiresAt))
    }

    @Test
    fun `the argument is set in its style where the template puts it`() {
        val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
        val shown = boldArgument("Shown as %1\$s", PHONE, bold)
        assertEquals("Shown as Pixel 9 Pro", shown.text)
        val span = shown.spanStyles.single()
        assertEquals(bold, span.item)
        assertEquals("Pixel 9 Pro", shown.text.substring(span.start, span.end))
        assertEquals("Pixel 9 Pro est affiché", boldArgument("%1\$s est affiché", PHONE, bold).text)
        assertThrows<IllegalArgumentException> { boldArgument("Shown as", PHONE, bold) }
        assertThrows<IllegalArgumentException> { boldArgument("%1\$s and %1\$s", PHONE, bold) }
    }
}
