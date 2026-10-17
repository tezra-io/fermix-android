package io.tezra.fermix.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer

// The rules of every event, in one place: the catalogue, the versions that carry each event and each
// field that only one version carries or requires, the raw tail, and every bound and rule on an
// event's fields. The sources are protocol.schema.json and PROTOCOL.md for protocol v1, and design
// section 7 for what protocol v2 adds or changes. A field that every version requires is a non-null
// property of its model without a default, and the shape check (ShapeCheck.kt) reads that, the
// field's type, its closed set and the ban on nulls from the model itself. The codec runs these rules
// on decode and on encode alike. The rules that are not an event's live with what they govern: the
// frame's bounds in Frame.kt; the JSON text's (its depth, raw control characters, bare values) and
// the envelope's `v`, `t` and `seq` in Envelope.kt; a run's in EventPartAssembler.kt, and a logical
// event's in Codec.kt; the pairing link's in PairingLink.kt and LinkQuery.kt.

internal const val V1 = 1
internal const val V2 = 2

/** The envelope versions this codec reads and writes; which of them a session accepts is core-session's. */
internal val VERSIONS = V1..V2
private val ONLY_V1 = V1..V1
private val ONLY_V2 = V2..V2
private val NEVER = IntRange.EMPTY

private const val MAX_HISTORY_LIMIT = 200L
private const val MAX_PAIR_TEXT_BYTES = 128
private const val MAX_CERTS = 6
private const val MAX_CHAIN_BYTES = 16_384
private const val MAX_STATUS_IDS = 32
private const val MAX_SEARCH_LIMIT = 50L
private const val MAX_QUERY_SCALARS = 256
private const val MAX_MESSAGE_BYTES = 512
private const val MAX_TOOL_DETAIL_BYTES = 512
private const val MAX_ROUTE_SCALARS = 1_024

/** The candidates a `hello_ack`, a `pair_approved` or a pairing link carries at most. */
internal const val MAX_CANDIDATES = 16

/** A candidate host's UTF-8 bytes at most, a DNS name's bound. */
internal const val MAX_HOST_BYTES = 253

private const val MAX_PROFILE_NAME_BYTES = 128
private const val MAX_PAGE_MESSAGES = 200
private const val MAX_LINK_PREVIEWS = 4
private const val MAX_URL_BYTES = 2_048
private const val MAX_SITE_BYTES = 120
private const val MAX_TITLE_BYTES = 300
private const val MAX_DESCRIPTION_BYTES = 600
private const val MAX_PENDING_APPROVALS = 4
private const val MAX_STATUS_PAGE = 32
private const val MAX_PLACES = 10
private const val MAX_CATEGORIES = 5
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** The relays an iroh route names at most (the schema's `irohCandidate.relays`). */
internal const val MAX_ROUTE_RELAYS = 8

/** An iroh endpoint id: 64 lowercase hex digits (the schema's `irohCandidate.node_id` and the link's `iroh`). */
internal val NODE_ID = Regex("[0-9a-f]{64}")

/** A relay list's revision: 16 lowercase hex digits (`relays_rev`, and `hello.relay_list_rev`). */
internal val RELAY_LIST_REV = Regex("[0-9a-f]{16}")

/** A relay's host as the route and the link carry it (the schema's `relay.url` without `https://` and `/`). */
internal const val RELAY_HOST_PATTERN = "[a-z0-9]([a-z0-9.-]*[a-z0-9])?"

private val RELAY_URL = Regex("https://$RELAY_HOST_PATTERN/")

private val COMMAND_NAME = Regex("[a-z0-9_]+")
private val SHA256 = Regex("[0-9a-fA-F]{64}")
private val TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}.*", RegexOption.DOT_MATCHES_ALL)
private val BASE64_32_BYTES = Regex("[A-Za-z0-9+/]{43}=")

/**
 * A field that only some versions carry, or require: elsewhere it is unknown, ignored on decode and
 * refused on encode.
 */
internal class VersionedField(
    val name: String,
    val carriedIn: IntRange,
    val requiredIn: IntRange = NEVER,
)

/**
 * One event of the catalogue: its model's descriptor and name, the versions that carry it, the
 * versions in which its frame carries a raw tail (never empty there, empty everywhere else), its
 * version-bound fields, and its bounds.
 */
internal class EventRule<in E>(
    val descriptor: SerialDescriptor,
    val versions: IntRange,
    val rawIn: IntRange,
    val versioned: List<VersionedField>,
    val check: FieldCheck.(E) -> Unit,
) {
    val t: String = descriptor.serialName
}

/** One direction's catalogue, keyed by `t`, with the polymorphic serializer of its models. */
internal class RuleBook<E : Any>(
    val serializer: KSerializer<E>,
    rules: List<EventRule<E>>,
) {
    val rules: Map<String, EventRule<E>> = rules.associateBy { it.t }
}

private inline fun <reified E : ClientEvent> client(
    versions: IntRange = VERSIONS,
    rawIn: IntRange = NEVER,
    versioned: List<VersionedField> = emptyList(),
    noinline rules: FieldCheck.(E) -> Unit = {},
): EventRule<ClientEvent> = EventRule(serializer<E>().descriptor, versions, rawIn, versioned) { rules(it as E) }

private inline fun <reified E : ServerEvent.Known> server(
    versions: IntRange = VERSIONS,
    rawIn: IntRange = NEVER,
    versioned: List<VersionedField> = emptyList(),
    noinline rules: FieldCheck.(E) -> Unit = {},
): EventRule<ServerEvent.Known> = EventRule(serializer<E>().descriptor, versions, rawIn, versioned) { rules(it as E) }

private val profileRules: FieldCheck.(String, Profile) -> Unit = { path, profile ->
    nonEmpty("$path.id", profile.id)
    nonEmpty("$path.name", profile.name)
    maxBytes("$path.name", profile.name, MAX_PROFILE_NAME_BYTES)
}

/** The iroh route's bounds (the schema's `irohCandidate` and `relay`); a skipped kind has none. */
private val candidateRules: FieldCheck.(String, Candidate) -> Unit = { path, candidate ->
    when (candidate) {
        is Candidate.Host -> {
            nonEmpty("$path.host", candidate.host)
            maxBytes("$path.host", candidate.host, MAX_HOST_BYTES)
            nonEmpty("$path.interface", candidate.networkInterface)
        }

        is Candidate.Iroh -> {
            matches("$path.node_id", candidate.nodeId, NODE_ID)
            size("$path.relays", candidate.relays, 1..MAX_ROUTE_RELAYS)
            each("$path.relays", candidate.relays) { relay, value ->
                matches("$relay.url", value.url, RELAY_URL)
                inRange("$relay.quic_port", value.quicPort.toLong(), 1L..MAX_PORT)
            }
            matches("$path.relays_rev", candidate.relaysRev, RELAY_LIST_REV)
        }

        // A kind this app does not know is skipped whatever it holds (PROTOCOL.md "Server events").
        is Candidate.Skipped -> {}
    }
}

private val modelRefRules: FieldCheck.(String, ModelRef) -> Unit = { path, model ->
    nonEmpty("$path.provider", model.provider)
    nonEmpty("$path.model", model.model)
    nonEmpty("$path.label", model.label)
}

private val capsRules: FieldCheck.(Caps) -> Unit = { caps ->
    each("caps.commands", caps.commands) { path, command ->
        nonEmpty("$path.name", command.name)
        each("$path.aliases", command.aliases) { alias, value -> nonEmpty(alias, value) }
        nonEmpty("$path.description", command.description)
    }
    inRange("caps.max_media_bytes", caps.maxMediaBytes, 0..Long.MAX_VALUE)
    caps.modelState?.let { state ->
        modelRefRules("caps.model_state.default", state.defaultModel)
        state.overrideModel?.let { modelRefRules("caps.model_state.override", it) }
    }
}

private val mediaRefRules: FieldCheck.(String, MediaRef) -> Unit = { path, media ->
    nonEmpty("$path.ref", media.ref)
    nonEmpty("$path.kind", media.kind)
    nonEmpty("$path.mime", media.mime)
    inRange("$path.size_bytes", media.sizeBytes, 0..Long.MAX_VALUE)
    matches("$path.sha256", media.sha256, SHA256)
    nonEmpty("$path.filename", media.filename)
    nonEmpty("$path.caption", media.caption)
}

/** A preview's bounds are UTF-8 bytes; `description` alone may be empty (PROTOCOL.md "Link previews"). */
private val linkCardRules: FieldCheck.(String, LinkPreviewCard) -> Unit = { path, card ->
    nonEmpty("${path}url", card.url)
    maxBytes("${path}url", card.url, MAX_URL_BYTES)
    nonEmpty("${path}site", card.site)
    maxBytes("${path}site", card.site, MAX_SITE_BYTES)
    nonEmpty("${path}title", card.title)
    maxBytes("${path}title", card.title, MAX_TITLE_BYTES)
    maxBytes("${path}description", card.description, MAX_DESCRIPTION_BYTES)
    nonEmpty("${path}image_ref", card.imageRef)
}

/**
 * A place's bounds (the schema's `place`): a name, at least one web address, its coordinates on the globe, and
 * each text the source gave non-empty, at most five categories.
 */
private val placeRules: FieldCheck.(String, Place) -> Unit = { path, place ->
    listOf("name" to place.name, "canonical_url" to place.canonicalUrl, "source_url" to place.sourceUrl)
        .forEach { (name, value) -> nonEmpty("$path.$name", value) }
    require(path, place.canonicalUrl != null || place.sourceUrl != null, "has neither canonical_url nor source_url")
    val (latitude, longitude) = place.location
    require("$path.location.latitude", latitude in -MAX_LATITUDE..MAX_LATITUDE, "is $latitude, off the globe")
    require("$path.location.longitude", longitude in -MAX_LONGITUDE..MAX_LONGITUDE, "is $longitude, off the globe")
    nonEmpty("$path.address", place.address)
    nonEmpty("$path.price_range", place.priceRange)
    size("$path.categories", place.categories, 1..MAX_CATEGORIES)
    each("$path.categories", place.categories) { category, value -> nonEmpty(category, value) }
    nonEmpty("$path.opening_hours.current_day", place.openingHours?.currentDay)
    nonEmpty("$path.distance.units", place.distance?.units)
}

/**
 * A reply's blocks (the companion PROTOCOL.md "Blocks under a reply"): at least one when the key is there, one block
 * of each type, a places block of one to ten places. A block of a type this app does not know is skipped whatever it
 * holds, as an unknown candidate is, so only the places block is held to a rule.
 */
private val blockRules: FieldCheck.(String, List<ReplyBlock>?) -> Unit = { path, blocks ->
    require(path, blocks == null || blocks.isNotEmpty(), "is empty; a reply with no block has no key")
    val places = blocks.orEmpty().count { it is ReplyBlock.Places }
    require(path, places <= 1, "holds $places places blocks; a reply has one block of each type")
    each(path, blocks) { block, value ->
        if (value is ReplyBlock.Places) {
            size("$block.places", value.places, 1..MAX_PLACES)
            each("$block.places", value.places, placeRules)
        }
    }
}

/** The fields a `row` shares with a history message (PROTOCOL.md "Timeline shapes"). */
private val timelineRules: FieldCheck.(String, HistoryMessage) -> Unit = { path, row ->
    positive("${path}server_seq", row.serverSeq)
    nonEmpty("${path}role", row.role)
    matches("${path}ts", row.ts, TIMESTAMP)
    each("${path}media_refs", row.mediaRefs, mediaRefRules)
    nonEmpty("${path}client_msg_id", row.clientMsgId)
    nonEmpty("${path}in_reply_to", row.inReplyTo)
    size("${path}link_previews", row.linkPreviews, 1..MAX_LINK_PREVIEWS)
    each("${path}link_previews", row.linkPreviews) { card, value -> linkCardRules("$card.", value) }
    blockRules("${path}blocks", row.blocks)
    require("${path}truncated", row.truncated != false, "is false; it is present only as true")
}

private val modelEntryRules: FieldCheck.(String, ModelEntry) -> Unit = { path, entry ->
    nonEmpty("$path.provider", entry.provider)
    val unlisted = entry.listingUnavailable != null
    val listed = listOf(entry.model, entry.label, entry.trait, entry.streams, entry.active, entry.isDefault)
    require("$path.listing_unavailable", !unlisted || entry.listingUnavailable == true, "is false")
    require("$path.listing_unavailable", !unlisted || listed.all { it == null }, "comes with a model")
    if (!unlisted) {
        listOf("model" to entry.model, "label" to entry.label).forEach { (name, value) ->
            present("$path.$name", value)
        }
        listOf("streams" to entry.streams, "active" to entry.active, "default" to entry.isDefault)
            .forEach { (name, value) -> present("$path.$name", value) }
    }
    nonEmpty("$path.model", entry.model)
    nonEmpty("$path.label", entry.label)
    nonEmpty("$path.trait", entry.trait)
}

private val attestationRules: FieldCheck.(Attestation) -> Unit = { attestation ->
    val lengths = attestation.certLengths
    size("attestation.cert_lengths", lengths, 1..MAX_CERTS)
    each("attestation.cert_lengths", lengths) { path, length -> require(path, length >= 1, "is $length bytes") }
    require("attestation", rawSize <= MAX_CHAIN_BYTES, "chain is $rawSize bytes, past $MAX_CHAIN_BYTES")
    require(
        "attestation.cert_lengths",
        lengths.sumOf {
            it.toLong()
        } == rawSize.toLong(),
        "do not sum to the $rawSize-byte tail",
    )
}

/**
 * Protocol v2's `push_register` is one of two forms: design section 7's [ClientEvent.PushRegister.platform] and
 * [ClientEvent.PushRegister.token], or the APNs token and environment the engine's v2 export carries, as v1 does.
 */
private fun FieldCheck.pushForm(register: ClientEvent.PushRegister) {
    if (version != V2) return
    val neutral = register.platform != null || register.token != null
    require(
        "apns_token",
        register.apnsToken == null || !neutral,
        "comes with platform or token; a register is one form",
    )
    if (register.apnsToken != null) {
        present("environment", register.environment)
        return
    }
    present("platform", register.platform)
    present("token", register.token)
}

/** `pair_request`'s texts, which the owner reads in the daemon's pairing prompt. */
private val PAIR_REQUEST_TEXTS = setOf("device_name", "model", "app_version")

/**
 * Refuses [value] as `pair_request`'s [field], `device_name`, `model` or `app_version`, by the rule the
 * codec encodes it under (PROTOCOL.md, the `pair_request` row): non-blank, at most 128 bytes of UTF-8, and
 * no lone surrogate or C0, C1 or DEL control character. For a caller that holds a text before the event
 * exists, such as a name the owner typed; it throws the ProtocolException.InvalidField the encoder would.
 */
fun requirePairRequestText(
    field: String,
    value: String,
) {
    require(field in PAIR_REQUEST_TEXTS) { "$field is none of pair_request's texts" }
    val t = serializer<ClientEvent.PairRequest>().descriptor.serialName
    FieldCheck(t, V2, rawSize = 0).printable(field, value, MAX_PAIR_TEXT_BYTES)
}

private val CLIENT_SESSION_RULES: List<EventRule<ClientEvent>> =
    listOf(
        client<ClientEvent.Hello>(
            versioned =
                listOf(VersionedField("relay_list_rev", ONLY_V2), VersionedField("last_mutation_seq", ONLY_V2)),
        ) {
            nonEmpty("device_id", it.deviceId)
            nonEmpty("app_version", it.appVersion)
            require("protocol_v", it.protocolV == version, "is ${it.protocolV}, not the envelope's $version")
            matches("relay_list_rev", it.relayListRev, RELAY_LIST_REV)
        },
        client<ClientEvent.PushRegister>(
            versioned =
                listOf(
                    VersionedField("apns_token", VERSIONS, ONLY_V1),
                    VersionedField("platform", ONLY_V2),
                    VersionedField("token", ONLY_V2),
                    VersionedField("environment", VERSIONS, ONLY_V1),
                ),
        ) {
            nonEmpty("apns_token", it.apnsToken)
            nonEmpty("token", it.token)
            require("environment", it.environment == null || it.platform != Platform.ANDROID, "is for ios alone")
            pushForm(it)
        },
        client<ClientEvent.Ack>(),
        client<ClientEvent.ReadState> { nonEmpty("profile_id", it.profileId) },
        client<ClientEvent.PairRequest>(
            rawIn = ONLY_V2,
            versioned =
                listOf(
                    VersionedField("platform", ONLY_V2, ONLY_V2),
                    VersionedField("attestation", ONLY_V2, ONLY_V2),
                ),
        ) {
            printable("device_name", it.deviceName, MAX_PAIR_TEXT_BYTES)
            printable("model", it.model, MAX_PAIR_TEXT_BYTES)
            printable("app_version", it.appVersion, MAX_PAIR_TEXT_BYTES)
            it.attestation?.let { attestation -> attestationRules(attestation) }
        },
        client<ClientEvent.Unpair>(),
        client<ClientEvent.Ping>(),
    )

private val CLIENT_CHAT_RULES: List<EventRule<ClientEvent>> =
    listOf(
        client<ClientEvent.Msg>(versioned = listOf(VersionedField("retry_of", ONLY_V2))) {
            nonEmpty("client_msg_id", it.clientMsgId)
            nonEmpty("profile_id", it.profileId)
            each("attach_ids", it.attachIds) { path, id -> nonEmpty(path, id) }
            require("text", it.text.isNotBlank() || it.attachIds.isNotEmpty(), "is blank and nothing is attached")
            nonEmpty("retry_of", it.retryOf)
        },
        client<ClientEvent.AttachBegin>(versioned = listOf(VersionedField("poster_of", ONLY_V2))) {
            nonEmpty("attach_id", it.attachId)
            nonEmpty("mime", it.mime)
            inRange("size_bytes", it.sizeBytes, 0..Long.MAX_VALUE)
            nonEmpty("name", it.name)
            nonEmpty("poster_of", it.posterOf)
            matches("sha256", it.sha256, SHA256)
        },
        client<ClientEvent.AttachChunk>(rawIn = VERSIONS) {
            nonEmpty("attach_id", it.attachId)
            inRange("index", it.index.toLong(), 0..Long.MAX_VALUE)
        },
        client<ClientEvent.AttachEnd> {
            nonEmpty("attach_id", it.attachId)
            matches("sha256", it.sha256, SHA256)
        },
        client<ClientEvent.Command> {
            nonEmpty("client_msg_id", it.clientMsgId)
            nonEmpty("profile_id", it.profileId)
            matches("name", it.name, COMMAND_NAME)
        },
        client<ClientEvent.Cancel> {
            nonEmpty("profile_id", it.profileId)
            nonEmpty("client_msg_id", it.clientMsgId)
        },
        client<ClientEvent.HistoryPull>(
            versioned = listOf(VersionedField("after_seq", VERSIONS, ONLY_V1), VersionedField("before_seq", ONLY_V2)),
        ) {
            nonEmpty("profile_id", it.profileId)
            inRange("limit", it.limit.toLong(), 1..MAX_HISTORY_LIMIT)
            require(
                "before_seq",
                it.afterSeq == null || it.beforeSeq == null,
                "comes with after_seq; a pull has one cursor",
            )
            present("after_seq", it.afterSeq ?: it.beforeSeq)
            positive("before_seq", it.beforeSeq)
        },
        client<ClientEvent.MediaFetch> { nonEmpty("ref", it.ref) },
    )

private val CLIENT_V2_RULES: List<EventRule<ClientEvent>> =
    listOf(
        client<ClientEvent.PushUnregister>(versions = ONLY_V2),
        client<ClientEvent.RequestStatus>(versions = ONLY_V2) {
            size("client_msg_ids", it.clientMsgIds, 1..MAX_STATUS_IDS)
            each("client_msg_ids", it.clientMsgIds) { path, id -> nonEmpty(path, id) }
        },
        client<ClientEvent.MutationsPull>(versions = ONLY_V2) {
            nonEmpty("profile_id", it.profileId)
            inRange("limit", it.limit.toLong(), 1..MAX_HISTORY_LIMIT)
        },
        client<ClientEvent.HistorySearch>(versions = ONLY_V2) {
            nonEmpty("profile_id", it.profileId)
            nonEmpty("query", it.query)
            maxScalars("query", it.query, MAX_QUERY_SCALARS)
            inRange("limit", it.limit.toLong(), 1..MAX_SEARCH_LIMIT)
            positive("before_seq", it.beforeSeq)
        },
        client<ClientEvent.ModelsPull>(versions = ONLY_V2),
    )

private val V2_HELLO_ACK =
    listOf("instance", "active_turns", "pending_approvals", "mutation_head_seq").map { VersionedField(it, ONLY_V2) }

private val helloAckRules: FieldCheck.(ServerEvent.HelloAck) -> Unit = {
    nonEmpty("session_id", it.sessionId)
    inRange("min_version", it.minVersion.toLong(), 1..Long.MAX_VALUE)
    inRange("max_version", it.maxVersion.toLong(), 1..Long.MAX_VALUE)
    require("max_version", it.minVersion <= it.maxVersion, "is below min_version ${it.minVersion}")
    each("profiles", it.profiles, profileRules)
    size("candidates", it.candidates, 0..MAX_CANDIDATES)
    each("candidates", it.candidates, candidateRules)
    capsRules(it.caps)
    it.instance?.let { instance ->
        listOf("label" to instance.label, "host" to instance.host, "profile" to instance.profile)
            .forEach { (name, value) -> nonEmpty("instance.$name", value) }
    }
    each("active_turns", it.activeTurns) { path, turn ->
        nonEmpty("$path.turn_id", turn.turnId)
        nonEmpty("$path.in_reply_to", turn.inReplyTo)
    }
    size("pending_approvals", it.pendingApprovals, 0..MAX_PENDING_APPROVALS)
    each("pending_approvals", it.pendingApprovals) { path, id -> nonEmpty(path, id) }
}

private val SESSION_RULES: List<EventRule<ServerEvent.Known>> =
    listOf(
        server<ServerEvent.HelloAck>(versioned = V2_HELLO_ACK, rules = helloAckRules),
        server<ServerEvent.Accepted> {
            nonEmpty("client_msg_id", it.clientMsgId)
            positive("server_seq", it.serverSeq)
            require(
                "server_seq",
                it.serverSeq == null || it.duplicate,
                "is on a first accepted, which never carries it",
            )
        },
        server<ServerEvent.PairApproved>(
            versioned = listOf(VersionedField("push_salt", ONLY_V2, ONLY_V2), VersionedField("push", ONLY_V2)),
        ) {
            nonEmpty("device_id", it.deviceId)
            size("candidates", it.candidates, 0..MAX_CANDIDATES)
            each("candidates", it.candidates, candidateRules)
            each("profiles", it.profiles, profileRules)
            matches("push_salt", it.pushSalt, BASE64_32_BYTES)
        },
        server<ServerEvent.PairDenied>(),
        server<ServerEvent.Error> {
            nonEmpty("code", it.code)
            nonEmpty("message", it.message)
            maxBytes("message", it.message, MAX_MESSAGE_BYTES)
            nonEmpty("client_msg_id", it.clientMsgId)
            nonEmpty("ref", it.ref)
            inRange("min_version", it.minVersion?.toLong(), 1..Long.MAX_VALUE)
            inRange("max_version", it.maxVersion?.toLong(), 1..Long.MAX_VALUE)
        },
        server<ServerEvent.Pong>(),
        server<ServerEvent.EventPart>(rawIn = VERSIONS) {
            inRange("count", it.count.toLong(), MIN_EVENT_PARTS.toLong()..MAX_EVENT_PARTS.toLong())
            inRange("index", it.index.toLong(), 0L until it.count.toLong())
        },
    )

private val MEDIA_RULES: List<EventRule<ServerEvent.Known>> =
    listOf(
        server<ServerEvent.AttachStatusEvent> { nonEmpty("attach_id", it.attachId) },
        server<ServerEvent.MediaBegin> {
            listOf("ref" to it.ref, "kind" to it.kind, "mime" to it.mime).forEach { (name, value) ->
                nonEmpty(name, value)
            }
            positive("server_seq", it.serverSeq)
            inRange("size_bytes", it.sizeBytes, 0..Long.MAX_VALUE)
            matches("sha256", it.sha256, SHA256)
            nonEmpty("filename", it.filename)
            nonEmpty("caption", it.caption)
        },
        server<ServerEvent.MediaChunk>(rawIn = VERSIONS) {
            nonEmpty("ref", it.ref)
            inRange("index", it.index.toLong(), 0..Long.MAX_VALUE)
        },
        server<ServerEvent.MediaEnd> {
            nonEmpty("ref", it.ref)
            matches("sha256", it.sha256, SHA256)
        },
    )

private val TURN_RULES: List<EventRule<ServerEvent.Known>> =
    listOf(
        server<ServerEvent.TurnStarted> {
            nonEmpty("profile_id", it.profileId)
            nonEmpty("turn_id", it.turnId)
            nonEmpty("in_reply_to", it.inReplyTo)
        },
        server<ServerEvent.TextDelta>(versioned = listOf(VersionedField("replace", ONLY_V2))) {
            nonEmpty("turn_id", it.turnId)
            require("replace", it.replace != false, "is false; it is present only as true")
        },
        server<ServerEvent.ToolEvent>(
            versioned = listOf(VersionedField("status", ONLY_V2)),
        ) {
            nonEmpty("turn_id", it.turnId)
            nonEmpty("tool", it.tool)
            maxBytes("detail", it.detail, MAX_TOOL_DETAIL_BYTES)
            nonEmpty("status", it.status)
        },
        server<ServerEvent.TextDone>(
            versioned = listOf(VersionedField("blocks", ONLY_V2), VersionedField("route", ONLY_V2)),
        ) {
            nonEmpty("turn_id", it.turnId)
            positive("server_seq", it.serverSeq)
            blockRules("blocks", it.blocks)
            require("truncated", it.truncated != false, "is false; it is present only as true")
            nonEmpty("route.provider", it.route?.provider)
            nonEmpty("route.model", it.route?.model)
        },
        server<ServerEvent.TurnError> {
            nonEmpty("turn_id", it.turnId)
            nonEmpty("code", it.code)
            nonEmpty("message", it.message)
            maxBytes("message", it.message, MAX_MESSAGE_BYTES)
        },
        server<ServerEvent.Approval> {
            val texts =
                listOf("approval_id" to it.approvalId, "kind" to it.kind, "text" to it.text, "token" to it.token)
            texts.forEach { (name, value) -> nonEmpty(name, value) }
            inRange("ttl_s", it.ttlS.toLong(), 1..Long.MAX_VALUE)
            listOf("approve_command" to it.approveCommand, "deny_command" to it.denyCommand).forEach { (name, value) ->
                nonEmpty(name, value)
                maxScalars(name, value, MAX_ROUTE_SCALARS)
            }
        },
        server<ServerEvent.ApprovalResolved> { nonEmpty("approval_id", it.approvalId) },
    )

private val TIMELINE_RULES: List<EventRule<ServerEvent.Known>> =
    listOf(
        // A history page's message carries `blocks` in protocol 2's export alone; a page is read at either version, so
        // the version table, which names an event's own fields, holds the row's and text_done's.
        server<ServerEvent.Row>(versioned = listOf(VersionedField("blocks", ONLY_V2))) {
            nonEmpty("profile_id", it.profileId)
            val message =
                HistoryMessage(
                    it.serverSeq,
                    it.role,
                    it.text,
                    it.ts,
                    it.mediaRefs,
                    it.kind,
                    it.clientMsgId,
                    it.inReplyTo,
                    it.metadata,
                    it.linkPreviews,
                    it.blocks,
                    it.truncated,
                )
            timelineRules("", message)
        },
        server<ServerEvent.Reaction> {
            nonEmpty("in_reply_to", it.inReplyTo)
            nonEmpty("emoji", it.emoji)
        },
        server<ServerEvent.LinkPreview> {
            positive("in_reply_to", it.inReplyTo)
            linkCardRules("", LinkPreviewCard(it.url, it.site, it.title, it.description, it.imageRef))
        },
        server<ServerEvent.ReadState> { nonEmpty("profile_id", it.profileId) },
        server<ServerEvent.HistoryPage>(versioned = listOf(VersionedField("prev_before_seq", ONLY_V2))) {
            nonEmpty("profile_id", it.profileId)
            size("messages", it.messages, 0..MAX_PAGE_MESSAGES)
            each("messages", it.messages) { path, message -> timelineRules("$path.", message) }
            positive("prev_before_seq", it.prevBeforeSeq)
        },
        server<ServerEvent.Notice> {
            nonEmpty("kind", it.kind)
            nonEmpty("text", it.text)
        },
    )

private val V2_SERVER_RULES: List<EventRule<ServerEvent.Known>> =
    listOf(
        server<ServerEvent.Thought>(versions = ONLY_V2) {
            nonEmpty("turn_id", it.turnId)
            nonEmpty("in_reply_to", it.inReplyTo)
        },
        server<ServerEvent.ThoughtDone>(versions = ONLY_V2) { nonEmpty("turn_id", it.turnId) },
        server<ServerEvent.TurnDone>(versions = ONLY_V2) { nonEmpty("turn_id", it.turnId) },
        server<ServerEvent.Models>(versions = ONLY_V2) { each("entries", it.entries, modelEntryRules) },
        server<ServerEvent.ModelChanged>(versions = ONLY_V2) {
            val texts =
                listOf(
                    "profile_id" to it.profileId,
                    "provider" to it.provider,
                    "model" to it.model,
                    "label" to it.label,
                )
            texts.forEach { (name, value) -> nonEmpty(name, value) }
            nonEmpty("note", it.note)
        },
        server<ServerEvent.Transcript>(versions = ONLY_V2) { nonEmpty("client_msg_id", it.clientMsgId) },
        server<ServerEvent.RequestStatusPage>(versions = ONLY_V2) {
            size("requests", it.requests, 0..MAX_STATUS_PAGE)
            each("requests", it.requests) { path, request ->
                nonEmpty("$path.client_msg_id", request.clientMsgId)
                nonEmpty("$path.turn_id", request.turnId)
                positive("$path.result_server_seq", request.resultServerSeq)
                nonEmpty("$path.error", request.error)
            }
        },
        server<ServerEvent.MutationsPage>(versions = ONLY_V2) {
            each("rows", it.rows) { path, row ->
                positive("$path.server_seq", row.serverSeq)
                positive("$path.mutation_seq", row.mutationSeq)
                each("$path.media_refs", row.mediaRefs, mediaRefRules)
            }
        },
        server<ServerEvent.SearchResults>(versions = ONLY_V2) {
            nonEmpty("profile_id", it.profileId)
            each("hits", it.hits) { path, hit ->
                positive("$path.server_seq", hit.serverSeq)
                nonEmpty("$path.role", hit.role)
                matches("$path.ts", hit.ts, TIMESTAMP)
                val scalars = hit.excerpt.codePointCount(0, hit.excerpt.length)
                each("$path.ranges", hit.ranges) { range, value ->
                    inRange("$range.start", value.start.toLong(), 0..Long.MAX_VALUE)
                    inRange("$range.length", value.length.toLong(), 1..Long.MAX_VALUE)
                    require(range, value.start.toLong() + value.length <= scalars, "runs past the excerpt")
                }
            }
            positive("next_before_seq", it.nextBeforeSeq)
        },
    )

/** The client catalogue: protocol v1's events and protocol v2's. */
internal val CLIENT_BOOK =
    RuleBook(serializer<ClientEvent>(), CLIENT_SESSION_RULES + CLIENT_CHAT_RULES + CLIENT_V2_RULES)

/** The server catalogue: protocol v1's events and protocol v2's. */
internal val SERVER_BOOK =
    RuleBook(
        serializer<ServerEvent.Known>(),
        SESSION_RULES + MEDIA_RULES + TURN_RULES + TIMELINE_RULES + V2_SERVER_RULES,
    )
