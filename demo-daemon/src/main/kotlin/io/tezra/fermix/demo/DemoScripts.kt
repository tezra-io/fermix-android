package io.tezra.fermix.demo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What each demo Fermix has said when the demo starts: its rows, oldest first, each so many minutes before the
// demo started, how many of the newest the owner has not read, and what is still under way: a long turn, an
// approval waiting for the owner. The words of the first are drawn from feature-chat's previews' (ChatPreviews.kt),
// the day a nightly export failed, so the demo and the reference screenshots tell one story. A row's time floats
// with the demo's start, so no words name a time of day, a weekday or a date that the time beside them could
// contradict (DemoAnswersTest reads every word the demo writes for one).

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** The owner's role on the wire, and the agent's. */
internal const val OWNER = "user"
internal const val AGENT = "assistant"

/** A link a scripted answer names, and its preview. */
class ScriptPreview(
    val url: String,
    val site: String,
    val title: String,
    val description: String,
    val image: DemoBlobName,
)

/** One row of a Fermix's history as the demo starts it. */
data class ScriptRow(
    val role: String,
    val text: String,
    val minutesAgo: Long,
    val clientMsgId: String? = null,
    val job: String? = null,
    val media: List<DemoBlobName> = emptyList(),
    val preview: ScriptPreview? = null,
) {
    /** The row's metadata: a job's delivery names its job (feature-chat's RowMetadata). */
    val metadata: JsonObject? get() = job?.let { JsonObject(mapOf("job" to JsonPrimitive(it))) }
}

/** A turn still running when the Fermix is paired: the chat that shows "thinking…". */
class LongTurn(
    val requestId: String,
    val headings: List<String>,
    val tools: List<String>,
    val answer: String,
)

/**
 * An approval waiting for the owner when the Fermix is paired, raised by the script's last request: its card, what
 * the grant changes ([grants]), and the words the request answers with once the owner grants it ([approved]).
 */
class ScriptApproval(
    val id: String,
    val kind: String,
    val text: String,
    val detail: String,
    val grants: String,
    val approved: String,
)

/** A Fermix's history and what is under way in it as the demo starts. */
class FermixScript(
    val rows: List<ScriptRow>,
    val unread: Int,
    val longTurn: LongTurn? = null,
    val approval: ScriptApproval? = null,
) {
    init {
        require(unread in 0..rows.size) { "$unread unread of ${rows.size} rows" }
    }
}

private fun you(
    text: String,
    minutesAgo: Long,
    id: String,
): ScriptRow = ScriptRow(OWNER, text, minutesAgo, clientMsgId = id)

private fun agent(
    text: String,
    minutesAgo: Long,
    job: String? = null,
): ScriptRow = ScriptRow(AGENT, text, minutesAgo, job = job)

private const val EXPORT_TABLE =
    "| Job | Took | Rows | Status |\n" +
        "|---|---|---|---|\n" +
        "| nightly-export | 60 s | 0 | timed out |\n" +
        "| photo-backup | 41 s | 2184 | ok |\n" +
        "| log-rotate | 2 s | 14 | ok |\n"

private val EXPORT_CODE =
    listOf(
        "suspend fun export(job: Job, timeout: Duration = 120.seconds): Report {",
        "    val started = clock.now()",
        "    val rows = mutableListOf<Row>()",
        "    withTimeout(timeout) {",
        "        source.pages(job.query).collect { page ->",
        "            rows += page.rows",
        "            progress.report(rows.size)",
        "        }",
        "    }",
        "    val file = exports.resolve(\"\${job.name}.csv\")",
        "    file.bufferedWriter().use { out ->",
        "        out.write(job.header)",
        "        rows.forEach { out.write(it.csv()) }",
        "    }",
        "    return Report(rows = rows.size, took = clock.now() - started, file = file)",
        "}",
    ).joinToString("\n")

/** A scripted row's time, so long before the demo started, in minutes. */
private fun ago(
    days: Long = 0,
    hours: Long = 0,
    minutes: Long = 0,
): Long = (days * HOURS_PER_DAY + hours) * MINUTES_PER_HOUR + minutes

/** suj-mbp: the day the nightly export failed, with every kind of row the chat draws, and an approval waiting. */
internal fun mainScript(): FermixScript =
    FermixScript(
        rows =
            listOf(
                you("Did any of the jobs fail?", ago(hours = 3), "demo-m1"),
                agent("The nightly export **failed**; the rest ran.", ago(hours = 2, minutes = 59)),
                you("Back up the photos folder before you clean anything up.", ago(hours = 2, minutes = 50), "demo-m2"),
                ScriptRow(
                    AGENT,
                    "Done. 2,184 photos are on the NAS; the newest two:",
                    ago(hours = 2, minutes = 48),
                    media = listOf(DemoBlobName.SUNRISE, DemoBlobName.HARBOUR),
                ),
                agent("They're under `backups/photos/`.", ago(hours = 2, minutes = 48)),
                you("Why did the export fail?", ago(hours = 2, minutes = 30), "demo-m3"),
                agent("It timed out at 60 s, the job's default.", ago(hours = 2, minutes = 29)),
                agent(
                    "The export now reads in pages and writes as it goes:\n\n" +
                        "```kotlin\n$EXPORT_CODE\n```\n\n$EXPORT_TABLE",
                    ago(hours = 2, minutes = 28),
                ),
                you("Can you send me the export's log?", ago(hours = 2), "demo-m4"),
                ScriptRow(AGENT, "Here it is.", ago(hours = 1, minutes = 59), media = listOf(DemoBlobName.EXPORT_LOG)),
                agent(EXPORT_TABLE, ago(hours = 1), job = "nightly-export"),
                you("What does the guide say about task timeouts?", ago(minutes = 45), "demo-m5"),
                ScriptRow(
                    AGENT,
                    "> A task started with `async` must be awaited, and `await` waits five seconds unless told " +
                        "otherwise.\n\nThe guide is [Task timeouts](https://hexdocs.pm/elixir/Task.html); the export " +
                        "uses its default.",
                    ago(minutes = 44),
                    preview =
                        ScriptPreview(
                            "https://hexdocs.pm/elixir/Task.html",
                            "hexdocs.pm",
                            "Task — Elixir",
                            "Conveniences for spawning and awaiting tasks.",
                            DemoBlobName.PREVIEW,
                        ),
                ),
                you("Restart the export service so it picks up the new timeout.", ago(minutes = 10), "demo-m6"),
                agent("Restarting a service needs your OK, so I've asked for it.", ago(minutes = 9)),
            ),
        unread = 2,
        approval =
            ScriptApproval(
                id = "sandbox-demo-restart-export",
                kind = "sandbox",
                text = "Allow restarting fermix-export.service?",
                detail = "systemctl --user restart fermix-export",
                grants = "commands + systemctl",
                approved = "Restarted `fermix-export`. It's running again with the 120 s timeout.",
            ),
    )

/** suj-mbp's development home: the build, and a test run still going when it is paired. */
internal fun devScript(): FermixScript =
    FermixScript(
        rows =
            listOf(
                you("Is the build still red?", ago(days = 1, hours = 3), "demo-d1"),
                agent(
                    "The build is green again: the flaky test now waits for the socket.",
                    ago(days = 1, hours = 2, minutes = 59),
                ),
                you("Run the full test suite and tell me what's slow.", ago(minutes = 2), "demo-d2"),
            ),
        unread = 0,
        longTurn =
            LongTurn(
                requestId = "demo-d2",
                headings = listOf("Running the test suite", "Reading the slowest tests' logs", "Ranking them"),
                tools = listOf("shell", "file_read"),
                answer =
                    "All 1,412 tests passed in 6 min 40 s. The slowest three:\n\n" +
                        "| Test | Took |\n|---|---|\n| UploadTest | 48 s |\n| LifetimeTest | 31 s |\n" +
                        "| PairingFailureTest | 22 s |\n\nEach waits on a real timer; a virtual clock would cut " +
                        "them to under a second.",
            ),
    )

/** studio: a reminder that came while the owner was away. */
internal fun studioScript(): FermixScript =
    FermixScript(
        rows =
            listOf(
                you("Remind me to renew the domain two days before it lapses.", ago(days = 2), "demo-s1"),
                agent(
                    "Scheduled: a reminder to renew the `fermix.dev` domain two days before it lapses.",
                    ago(days = 1, hours = 23, minutes = 59),
                ),
                agent(
                    "Renew the `fermix.dev` domain: it lapses in two days.",
                    ago(hours = 3),
                    job = "domain-renewal",
                ),
            ),
        unread = 1,
    )

/**
 * nas: the photo backups' daily reports for [NAS_DAYS] days, more than the newest page a phone pulls first, so
 * scrolling up its chat loads an older page; three unread.
 */
internal fun nasScript(): FermixScript =
    FermixScript(
        rows =
            (NAS_DAYS downTo 1).map { day -> agent(backupReport(day), ago(days = day.toLong()), "photo-backup") } +
                agent("The disk is 81% full: 1.6 TB of 2 TB.", ago(hours = 20), job = "disk-check"),
        unread = 3,
    )

/** The days of nas's backup reports: past the 50 rows of a phone's first page (core-session's BACKWARD_PAGE_LIMIT). */
internal const val NAS_DAYS = 80

/** The backup report of [day] days ago, its numbers fixed by the day alone. */
private fun backupReport(day: Int): String {
    val photos = (day * BACKUP_STRIDE) % BACKUP_SPREAD + 2
    val seconds = photos / PHOTOS_PER_SECOND + 1
    return "Backed up $photos photos in $seconds s."
}

private const val BACKUP_STRIDE = 37
private const val BACKUP_SPREAD = 140
private const val PHOTOS_PER_SECOND = 14

/** build-box: quiet since earlier in the week. */
internal fun buildScript(): FermixScript =
    FermixScript(
        rows =
            listOf(
                you("Clear the old build caches.", ago(days = 4, hours = 2), "demo-b1"),
                agent("Cleared 38 GB of caches older than a week.", ago(days = 4, hours = 1, minutes = 58)),
            ),
        unread = 0,
    )

/** pi-garden, whose agent its owner named Basil: last heard from twelve days ago. */
internal fun gardenScript(): FermixScript =
    FermixScript(
        rows =
            listOf(
                agent(
                    "The soil in the tomato bed is dry: 18% moisture.",
                    ago(days = 12, hours = 5),
                    job = "soil-check",
                ),
                you("Water it for ten minutes.", ago(days = 12, hours = 4), "demo-g1"),
                agent(
                    "Watered the tomato bed for ten minutes. Moisture is back to 34%.",
                    ago(days = 12, hours = 3, minutes = 49),
                ),
            ),
        unread = 0,
    )
