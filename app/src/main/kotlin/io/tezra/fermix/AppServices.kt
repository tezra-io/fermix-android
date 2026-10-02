package io.tezra.fermix

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.attest.HardwareGate
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.RoomSessionStore
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.onboarding.OnboardingParts
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.onboarding.deviceNameRefusal
import io.tezra.fermix.onboarding.handleStarter
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.PairingParts
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.WebSocketDialer
import io.tezra.fermix.session.deviceModel
import io.tezra.fermix.transport.NetworkWatcher
import io.tezra.fermix.transport.PinnedTrust
import io.tezra.fermix.transport.WebSocketConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** The profile a pairing's first chat opens on (design section 9.1: always `main` in this version). */
private const val FIRST_PROFILE = "main"

/** Where the records and each instance's files live: credential-encrypted, no backup (design section 6.6). */
private const val RECORDS_FILE = "instances.json"
private const val INSTANCES_DIRECTORY = "instances"

/**
 * What the app runs on for as long as its process lives, made once by [FermixApplication]: the instance
 * records and their databases, the network facts, the device keys, the socket connector, and the two
 * facts the pairing-wait notification follows, [pairingWait] (onboarding's) and [inBackground] (the
 * activity's). Blocking work, the files and the Keystore, runs on [io]; a ceremony runs on [work].
 */
class AppServices(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val databases = ProfileDatabases(context, File(context.noBackupFilesDir, INSTANCES_DIRECTORY))
    val instances = InstanceStore(instanceDataStore(File(context.noBackupFilesDir, RECORDS_FILE), scope), databases)
    private val network = NetworkWatcher(context)
    private val keys = DeviceKeys()
    private val connector = WebSocketConnector()

    /** What Verify waits for, while it shows; the pairing-wait notification's facts (design section 12.5). */
    val pairingWait = MutableStateFlow<PairingWait?>(null)

    /** Whether the activity is out of sight, which is when the pairing wait needs its notification. */
    val inBackground = MutableStateFlow(true)

    /** Starts reading the network, for the process's lifetime. */
    fun start() = network.start()

    /** Onboarding's parts, for the ViewModel the activity keeps. */
    fun onboardingParts(): OnboardingParts =
        OnboardingParts(
            gate = { HardwareGate.check(context.packageManager) },
            pairings = handleStarter(keys, ::pairingParts),
            identity = phoneIdentity(context),
            instances = instances,
            network = network.facts,
            pairingDispatcher = work,
            pairingWait = pairingWait,
        )

    /**
     * What a pairing over [link] runs on: a pinned WebSocket dialer, the first profile's store, and an
     * announcer that keeps each row the paired session hands over. The chat that shows and notifies them
     * comes later; until then a row is kept and not announced, so it is never acked and comes again.
     */
    private fun pairingParts(link: PairingLink): PairingParts {
        val database = databases.open(instanceIdOf(link.gatewayPublicKey), FIRST_PROFILE)
        return PairingParts(
            dialerFor = { port, pin -> WebSocketDialer(connector, port, PinnedTrust(pin)) },
            profileId = FIRST_PROFILE,
            store = RoomSessionStore(database),
            announcer = keepingAnnouncer(database),
            network = network.facts,
            keystore = io,
        )
    }
}

private fun keepingAnnouncer(database: ProfileDatabase): Announcer =
    Announcer { row ->
        database.timeline().persist(row)
        Announcement.NOT_ANNOUNCED
    }

/** An instance's id, as data keys its files: `sha256(gateway_pk)` in lowercase hex (design section 9.1). */
private fun instanceIdOf(gatewayPublicKey: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gatewayPublicKey))

/**
 * This phone to a daemon (design section 6.3): the name its owner gave it in Settings, or its model when
 * that name is none `pair_request` can carry; `Build.MANUFACTURER + Build.MODEL`; and the app's version.
 */
private fun phoneIdentity(context: Context): PhoneIdentity {
    val named = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()
    val deviceName = if (named.isNullOrEmpty() || deviceNameRefusal(named) != null) Build.MODEL else named
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    return PhoneIdentity(
        deviceName = deviceName,
        model = deviceModel(Build.MANUFACTURER, Build.MODEL),
        appVersion = checkNotNull(version) { "the app's manifest names its version" },
    )
}
