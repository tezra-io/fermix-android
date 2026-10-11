package io.tezra.fermix.chats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.RepairNotice
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.AvatarPictureCache
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.LocalAvatarPictures
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The Chats list, its trust states and the app lock at the twelve windows of @FermixPreviews.
// The list holds three Fermixes: production on suj-mbp over Tailscale with two unread, the dev daemon on
// the same Mac connecting with a draft and its DEV tag, and a Linux box that unpaired this phone. The rows'
// other states are a list of their own, as the canon's trust-state frame: the dev daemon thinking, a
// daemon reinstalled, a connection taken over, a protocol error on the one the owner named Studio and gave a photo,
// and a Fermix a restore dropped. "Send to which Fermix?" lists the canon's three: suj-mbp and its dev daemon, both
// up, and suj-linux with no link. The photo is drawn as the preview composes, a fixed pose (a hill under a morning
// sky), and read through the app's picture cache as a row reads one.

private val NO_ACTIONS =
    ChatsActions(
        onAdd = {},
        onAppLock = {},
        onOpen = {},
        onMoveToTop = {},
        onRename = { _, _ -> },
        onDetails = {},
        onUnpair = {},
        onRepair = {},
        onDismissRepair = {},
    )

private val THREE =
    ChatsUi(
        rows =
            listOf(
                ChatRow(
                    record = sample(1, tint = "Slate"),
                    profileId = "main",
                    agentName = null,
                    dev = false,
                    link = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true),
                    line = RowLine.Message("Raised the export timeout and re-ran the job."),
                    time = "09:41",
                    unread = 2,
                ),
                ChatRow(
                    record = sample(2, profile = "fermix-dev", tint = "Ocean"),
                    profileId = "main",
                    agentName = null,
                    dev = true,
                    link = Link.Connecting,
                    line = RowLine.Draft("restart the ingest worker after the nightly export finishes"),
                    time = "09:40",
                    unread = 0,
                ),
                ChatRow(
                    record = sample(3, host = LINUX_HOST, tint = "Sage"),
                    profileId = "main",
                    agentName = null,
                    dev = false,
                    link = Link.Revoked,
                    line = RowLine.Speaks(Link.Revoked),
                    time = "Tue",
                    unread = 0,
                ),
            ),
        repairs = emptyList(),
    )

/** When the owner gave Studio its photo. */
private const val PICTURED_AT = 1_790_502_060_000L

/** A photo for a preview's avatar, [side] pixels across: a green hill under a morning sky. */
private fun hillPhoto(side: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val edge = side.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, edge, 0xFF9CC9EE.toInt(), 0xFFE8F1F8.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, edge, edge, paint)
    paint.shader = null
    paint.color = 0xFF5E9A4F.toInt()
    canvas.drawCircle(edge * 0.3f, edge * 1.25f, edge * 0.75f, paint)
    paint.color = 0xFF3F7A3A.toInt()
    canvas.drawCircle(edge * 0.95f, edge * 1.35f, edge * 0.7f, paint)
    return bitmap
}

/**
 * [content] with the app's picture cache over [hillPhoto], read at once on [io], so a pictured row shows in the
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
                hillPhoto(side)
            }, log = { _, _ -> }, io)
        }
    CompositionLocalProvider(LocalAvatarPictures provides pictures, content = content)
}

/** A row of [record] whose link and second line are [link] and [line], at [time], nothing unread. */
private fun stateRow(
    record: Instance,
    link: Link,
    line: RowLine,
    time: String,
): ChatRow =
    ChatRow(
        record = record,
        profileId = "main",
        agentName = null,
        dev = record.profile == "fermix-dev",
        link = link,
        line = line,
        time = time,
        unread = 0,
    )

private val STATES =
    ChatsUi(
        rows =
            listOf(
                stateRow(
                    sample(2, profile = "fermix-dev", tint = "Ocean"),
                    Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
                    RowLine.Thinking,
                    "09:40",
                ),
                stateRow(sample(4, tint = "Clay"), Link.IdentityChanged, RowLine.Speaks(Link.IdentityChanged), "Mon"),
                stateRow(
                    sample(3, host = LINUX_HOST, tint = "Sage"),
                    Link.Replaced,
                    RowLine.Speaks(Link.Replaced),
                    "Tue",
                ),
                stateRow(
                    sample(5, nickname = "Studio", tint = "Plum").copy(pictureAt = PICTURED_AT),
                    Link.ProtocolError,
                    RowLine.Speaks(Link.ProtocolError),
                    "Wed",
                ),
            ),
        repairs = listOf(RepairNotice(sample(6).id, "Lab")),
    )

@FermixPreviews
@Composable
fun ChatsListPreview() {
    FermixPreviewTheme { ChatsScreen(ui = THREE, actions = NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun ChatsRowStatesPreview() {
    FermixPreviewTheme { WithPhotos { ChatsScreen(ui = STATES, actions = NO_ACTIONS) } }
}

@FermixPreviews
@Composable
fun ChatsEmptyPreview() {
    FermixPreviewTheme { ChatsScreen(ui = ChatsUi(rows = emptyList(), repairs = emptyList()), actions = NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun RevokedPreview() {
    FermixPreviewTheme { TrustScreen(Trust.REVOKED, HOST, onBack = {}, onPairAgain = {}, onRemove = {}) }
}

@FermixPreviews
@Composable
fun IdentityChangedPreview() {
    FermixPreviewTheme { TrustScreen(Trust.IDENTITY_CHANGED, HOST, onBack = {}, onPairAgain = {}, onRemove = {}) }
}

@FermixPreviews
@Composable
fun AppLockPreview() {
    FermixPreviewTheme { LockScreen(onUnlock = {}) }
}

@FermixPreviews
@Composable
fun AppLockSettingPreview() {
    FermixPreviewTheme { AppLockScreen(on = true, available = true, onBack = {}, onChange = {}) }
}

private val SHARE_ROWS =
    listOf(
        sample(1, tint = "Slate") to Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
        sample(2, profile = "fermix-dev", tint = "Ocean") to
            Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true),
        sample(3, host = LINUX_HOST, tint = "Sage") to Link.NotOpen,
    ).map { (record, link) ->
        ChatRow(
            record = record,
            profileId = "main",
            agentName = null,
            dev = record.profile == "fermix-dev",
            link = link,
            line = RowLine.Empty,
            time = null,
            unread = 0,
        )
    }

/**
 * The share sheet as ModalBottomSheet draws it, at most 640 dp wide and centred (BottomSheetDefaults.SheetMaxWidth),
 * its grab bar on top, over the scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@FermixPreviews
@Composable
fun ShareSheetPreview() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        Box(modifier = Modifier.fillMaxSize().background(colors.scrim), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ColumnWidth.Wide.width)
                        .fillMaxWidth()
                        .background(colors.tonalSolid, FermixShapes.sheet),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BottomSheetDefaults.DragHandle()
                ShareSheetContent(SHARE_ROWS, onPick = {})
            }
        }
    }
}
