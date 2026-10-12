package io.tezra.fermix.design

import io.tezra.fermix.data.EYE_STYLES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.hypot

/**
 * The avatar's six eye styles (the owner, 2026-10-11: "Just different style of eyes is fine"), each a still pose of the
 * mark's eyes. An instance record keeps its style by name and refuses a name not in data's EYE_STYLES, since data does
 * not depend on this module; the two are held equal here, as TintNamesTest holds the tints.
 */
class EyeStylesTest {
    @Test
    fun `data's eye styles are this enum's, in its order`() {
        assertEquals(EyeStyle.entries.map { it.name }, EYE_STYLES)
    }

    @Test
    fun `each style is a pose of the mark's eyes, open, happy, a wink, a squint, shut and a look aside`() {
        val expected =
            listOf(
                EyeStyle.Open to MarkPose(),
                EyeStyle.Happy to MarkPose(happy = 1f),
                EyeStyle.Wink to MarkPose(blinkRight = 0.1f),
                EyeStyle.Squint to MarkPose(squint = 0.4f),
                EyeStyle.Sleepy to MarkPose(blink = 0.1f),
                EyeStyle.Sideways to MarkPose(eyeX = -11f),
            )
        assertEquals(expected, EyeStyle.entries.map { it to it.pose })
    }

    @Test
    fun `a look aside moves the pair 4 dp or more on the chat bar's 32 dp avatar, and keeps it on the disc`() {
        val left = MarkGeometry.leftEye
        val pair = MarkGeometry.rightEye.centre.x + MarkGeometry.rightEye.width / 2f - (left.centre.x - left.width / 2f)
        val dpPerUnit = EYE_SPAN * 32f / pair
        assertTrue(AVATAR_LOOK * dpPerUnit >= 4f, "the look is ${AVATAR_LOOK * dpPerUnit} dp on 32 dp")
        // The left eye's outer corner, looking aside, from the disc's centre: inside the rim, in mark units.
        val corner = hypot(pair / 2f + AVATAR_LOOK, left.height / 2f)
        val rim = pair / EYE_SPAN / 2f
        assertTrue(corner < rim, "the eye's corner is $corner units out, the rim $rim")
    }

    @Test
    fun `a squint narrows the eyes until they are wider than tall, and short of shut`() {
        val eye = MarkGeometry.leftEye
        assertTrue(eye.height * AVATAR_SQUINT < eye.width, "a squinting eye is ${eye.height * AVATAR_SQUINT} tall")
        assertTrue(AVATAR_SQUINT > 2f * EYE_SHUT, "a squint of $AVATAR_SQUINT reads as shut")
    }

    @Test
    fun `a shut eye is the player's, the one Securing's blink closes to`() {
        assertEquals(0.1f, EYE_SHUT)
        assertEquals(EYE_SHUT, Open.blink.single { it.ms == 110 }.value)
    }

    @Test
    fun `a style is found by its name, and any other name fails`() {
        EyeStyle.entries.forEach { style -> assertEquals(style, eyeStyleOf(style.name)) }
        for (name in listOf("open", "", "Wide", "Sideways ")) {
            assertThrows<IllegalArgumentException> { eyeStyleOf(name) }
        }
    }
}
