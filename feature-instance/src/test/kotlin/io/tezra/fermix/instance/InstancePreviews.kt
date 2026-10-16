package io.tezra.fermix.instance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Reachability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneOffset

// The Instance screen at the twelve windows of @FermixPreviews: a Fermix the owner named and gave a photo,
// connected over the LAN with Tailscale likely up, after a test whose race its tailnet address won, so that both its
// candidates are lit as the canon draws them, each by its own handshake, with a few lines in its log, its page's
// top, and its foot with "Advanced" open; "Advanced" again over the daemon's iroh route, from a network neither host
// is on; and the foot of the page of one that cannot be reached, without FCM, on a debug build; and the Picture
// sheet's content on its tonal surface, the eight tints, the six eye styles on Coral,
// Happy chosen, and "Remove photo". The pairing's day is read in UTC, so the images are the same in every zone. The
// photo is drawn as the preview composes, a fixed pose: a dusk over the sea, read through the app's picture cache as a
// screen reads one.

private val LOG =
    listOf(
        Diagnostic(atMs = 2_000, kind = DiagnosticKind.CLOSED, detail = "1000 by daemon"),
        Diagnostic(atMs = 95_000, kind = DiagnosticKind.UNKNOWN_EVENT, detail = "t=call_offer"),
    )

/** What the rail notes as a session opens over the route: the endpoint bound, and a relay that answered late. */
private val REMOTE_LOG =
    listOf(
        Diagnostic(atMs = 1_000, kind = DiagnosticKind.ROUTE, detail = "the iroh endpoint is bound, with 2 relays"),
        Diagnostic(
            atMs = 11_000,
            kind = DiagnosticKind.ROUTE,
            detail = "a relay failed before another answered: us1.relay.fermix.ai: timed out after 10 s",
        ),
    )

private val NO_ACTIONS =
    InstanceActions(
        onBack = {},
        onRename = {},
        onTest = {},
        onNotifications = {},
        onPreviews = {},
        onClearCache = {},
        onUnpair = {},
        onTint = {},
        onEyes = {},
        onChoosePhoto = {},
        onRemovePhoto = {},
    )

/** A photo for a preview's avatar, [side] pixels across: a dusk sky, its sun, and the sea under it. */
private fun duskPhoto(side: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val edge = side.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, edge, 0xFFF2B880.toInt(), 0xFF7A6BC4.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, edge, edge, paint)
    paint.shader = null
    paint.color = 0xFFFFE9A8.toInt()
    canvas.drawCircle(edge * 0.5f, edge * 0.64f, edge * 0.2f, paint)
    paint.color = 0xFF27407A.toInt()
    canvas.drawRect(0f, edge * 0.66f, edge, edge, paint)
    return bitmap
}

/**
 * [content] with the app's picture cache over [duskPhoto], read at once on [io], so a pictured avatar shows in the
 * image.
 */
@Composable
private fun WithPhotos(
    io: CoroutineDispatcher = Dispatchers.Unconfined,
    content: @Composable () -> Unit,
) {
    val pictures =
        remember {
            AvatarPictureCache(read = {
                _,
                side,
                ->
                duskPhoto(side)
            }, log = { _, _ -> }, io)
        }
    CompositionLocalProvider(LocalAvatarPictures provides pictures, content = content)
}

@FermixPreviews
@Composable
fun InstanceScreenPreview() {
    val record = sample(nickname = "Studio").copy(pictureAt = PAIRED_AT)
    val test = TestState.Done(TestOutcome.Reached(TAILNET, millis = 38))
    FermixPreviewTheme {
        WithPhotos {
            InstanceScreen(
                ui =
                    InstanceUi(
                        record = record,
                        others = emptyList(),
                        link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
                        diagnostics = LOG,
                        reachable = reachableCandidates(record.candidates, LAN, test, Reachability.TAILNET_LIKELY),
                        previews = true,
                        cacheBytes = 184_000_000,
                        test = test,
                        releaseBuild = true,
                    ),
                actions = NO_ACTIONS,
                zone = ZoneOffset.UTC,
            )
        }
    }
}

@FermixPreviews
@Composable
fun InstanceScreenAdvancedPreview() {
    // The screen's "Advanced", open, in the page's column: the connection, this phone, the cache, the log and the
    // name's reset.
    val record = sample(nickname = "Studio")
    val test = TestState.Done(TestOutcome.Reached(TAILNET, millis = 38))
    val ui =
        InstanceUi(
            record = record,
            others = emptyList(),
            link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
            diagnostics = LOG,
            reachable = reachableCandidates(record.candidates, LAN, test, Reachability.TAILNET_LIKELY),
            previews = true,
            cacheBytes = 184_000_000,
            test = test,
            releaseBuild = true,
        )
    FermixPreviewTheme {
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Advanced(ui, ZoneOffset.UTC, NO_ACTIONS, startsOpen = true)
            }
        }
    }
}

@FermixPreviews
@Composable
fun InstanceScreenRemotePreview() {
    // "Advanced" open while the session is up over the daemon's iroh route (M62): the path "From anywhere", the hosts
    // unlit, the route's row after them, lit, with its relays, and the rail's lines in the log.
    val record = sample(nickname = "Studio").copy(route = ROUTE)
    val ui =
        InstanceUi(
            record = record,
            others = emptyList(),
            link = Link.Up(Candidate.Scope.REMOTE, latencyMs = 41, caughtUp = true),
            diagnostics = REMOTE_LOG,
            reachable = reachableCandidates(record.raced, ROUTE, TestState.Idle, Reachability.TAILSCALE_OFF),
            previews = true,
            cacheBytes = 184_000_000,
            test = TestState.Idle,
            releaseBuild = true,
        )
    FermixPreviewTheme {
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Advanced(ui, ZoneOffset.UTC, NO_ACTIONS, startsOpen = true)
            }
        }
    }
}

@FermixPreviews
@Composable
fun InstanceScreenFootPreview() {
    FermixPreviewTheme {
        InstanceScreen(
            ui =
                InstanceUi(
                    record = sample(push = emptyList()),
                    others = emptyList(),
                    link = Link.CannotReach,
                    diagnostics = LOG,
                    reachable = emptySet(),
                    previews = true,
                    cacheBytes = 0,
                    test = TestState.Done(TestOutcome.NotReached(failed = setOf(LAN, TAILNET))),
                    releaseBuild = false,
                ),
            actions = NO_ACTIONS,
            zone = ZoneOffset.UTC,
            // Past the end: the page settles at its foot.
            scroll = rememberScrollState(initial = Int.MAX_VALUE),
        )
    }
}

@FermixPreviews
@Composable
fun PictureSheetPreview() {
    val record = sample(nickname = "Studio").copy(tint = "Coral", eyes = "Happy", pictureAt = PAIRED_AT)
    FermixPreviewTheme {
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.background(LocalFermixColors.current.tonalSolid)) {
                PictureSheetContent(record, onTint = {}, onEyes = {}, onChoose = {}, onRemove = {})
            }
        }
    }
}
