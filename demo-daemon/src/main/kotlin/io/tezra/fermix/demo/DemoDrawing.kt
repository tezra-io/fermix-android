package io.tezra.fermix.demo

import kotlin.math.abs

// The picture the demo's image turn draws (DemoTurns), made from fixed numbers as the demo's other pictures are.

// The drawing's lighthouse: a dusk sky over the sea, a striped tower on a rock, its lamp and the beam it throws.
private const val DUSK_TOP = 0x2A3A6E
private const val DUSK_LOW = 0xC9A6B4
private const val ROCK = 0x2C2E33
private const val STRIPE_RED = 0xB8443A
private const val STRIPE_WHITE = 0xF2EFE8
private const val LAMP = 0xFFE7A0
private const val DRAWING_HORIZON = 0.68f
private const val TOWER_ACROSS = 0.38f
private const val TOWER_TOP = 0.3f
private const val TOWER_HALF_WIDTH = 22
private const val TOWER_STRIPES = 5
private const val LAMP_RADIUS = 16
private const val ROCK_HEIGHT = 26
private const val ROCK_HALF_WIDTH = 78
private const val BEAM_SPREAD = 0.16f
private const val BEAM_LIGHT = 0.45f

/**
 * The image turn's drawing: a lighthouse on a rock against a dusk sky, its lamp lit and its beam thrown to the right
 * over the sea.
 */
internal fun lighthouse(
    x: Int,
    y: Int,
): Int {
    val horizon = (DRAWING_SIDE * DRAWING_HORIZON).toInt()
    val towerX = (DRAWING_SIDE * TOWER_ACROSS).toInt()
    val top = (DRAWING_SIDE * TOWER_TOP).toInt()
    val lampY = top - LAMP_RADIUS
    val inLamp = (x - towerX) * (x - towerX) + (y - lampY) * (y - lampY) < LAMP_RADIUS * LAMP_RADIUS
    val inTower = x in towerX - TOWER_HALF_WIDTH..towerX + TOWER_HALF_WIDTH && y in top until horizon
    val inBeam = x > towerX && abs(y - lampY) < (x - towerX) * BEAM_SPREAD
    val onRock = abs(y - horizon) < ROCK_HEIGHT && abs(x - towerX) < ROCK_HALF_WIDTH
    val back = if (y < horizon) mix(DUSK_TOP, DUSK_LOW, y.toFloat() / horizon) else seaAt(y, horizon)
    return when {
        inLamp -> LAMP
        inTower -> towerStripe(y, top, horizon)
        onRock -> ROCK
        inBeam -> mix(back, LAMP, BEAM_LIGHT)
        else -> back
    }
}

/** The sea below the [horizon], darkening towards the foot of the drawing. */
private fun seaAt(
    y: Int,
    horizon: Int,
): Int = mix(SEA_FAR, SEA_NEAR, (y - horizon).toFloat() / (DRAWING_SIDE - horizon))

/** The tower's band at [y], red and white in turn from its [top] to the [horizon]. */
private fun towerStripe(
    y: Int,
    top: Int,
    horizon: Int,
): Int {
    val band = (y - top) * TOWER_STRIPES / (horizon - top)
    return if (band % 2 == 0) STRIPE_RED else STRIPE_WHITE
}
