package io.tezra.fermix.design

import androidx.compose.ui.geometry.Offset

/**
 * The Fermix wordmark as design/wordmark/fermix-wordmark.svg writes it (the owner, 2026-10-10), the engine's
 * `fermix_wordmark/1` saved by fermix-macos: its viewBox, each group's translation and path data as the file's own
 * strings, and the two eye-dots. Written from the file, never edited; WordmarkGeometryTest holds it to the file, group
 * for group and path for path, and the file to its digest.
 */
internal object WordmarkGeometry {
    /** The viewBox: the letters' 384 by 100 units with the file's margin, 6 at the sides and 8 above and below. */
    const val LEFT = -6f
    const val TOP = -8f
    const val WIDTH = 396f
    const val HEIGHT = 116f

    /** The letters, filled together in the current colour by the even-odd rule: F, E, R, M, the I's stem and X. */
    val letters =
        listOf(
            WordmarkGroup(
                Offset(0f, 0f),
                listOf("M9,0 H17 V100 H0 V9 Z", "M17,0 H56 V9 L48,17 H17 Z", "M17,41 H48 V50 L40,58 H17 Z"),
            ),
            WordmarkGroup(
                Offset(69f, 0f),
                listOf(
                    "M9,0 H17 V100 H0 V9 Z",
                    "M17,0 H56 V9 L48,17 H17 Z",
                    "M17,41 H48 V50 L40,58 H17 Z",
                    "M17,83 H56 V100 H17 Z",
                ),
            ),
            WordmarkGroup(
                Offset(138f, 0f),
                listOf(
                    "M9,0 H17 V100 H0 V9 Z " +
                        "M17,0 H44 Q58,0 58,18 V34 Q58,52 44,52 H30 L46,52 L58,100 H41 L30,52 H17 Z " +
                        "M17,17 H38 Q41,17 41,20 V32 Q41,35 38,35 H17 Z",
                ),
            ),
            WordmarkGroup(
                Offset(209f, 0f),
                listOf("M0,100 V0 H18 L36,47 L54,0 H72 V100 H55 V34 L41,70 H31 L17,34 V100 Z"),
            ),
            WordmarkGroup(Offset(294f, 0f), listOf("M0,40 H17 V100 H0 Z")),
            WordmarkGroup(
                Offset(324f, 0f),
                listOf(
                    "M0,0 L17,0 L30,30 L21.5,50 Z",
                    "M0,100 L17,100 L30,70 L21.5,50 Z",
                    "M60,0 L43,0 L30,30 L38.5,50 Z",
                    "M60,100 L43,100 L30,70 L38.5,50 Z",
                ),
            ),
        )

    /** The eye-dots' group's translation, over the I's stem, and their centres in it. */
    val dotsAt = Offset(294f, 0f)
    val dots = listOf(Offset(2f, 21f), Offset(15f, 21f))

    /** Each dot's radius; the file fills both `#2b5cff`, which is [FermixColors.signal] in both modes. */
    const val DOT_RADIUS = 4.7f
}

/** One of the file's letter groups: its `translate(x y)` and the data of each `path` in it, in its order. */
internal data class WordmarkGroup(
    val at: Offset,
    val paths: List<String>,
)
