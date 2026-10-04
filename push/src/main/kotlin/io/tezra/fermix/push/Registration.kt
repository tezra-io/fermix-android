package io.tezra.fermix.push

import io.tezra.fermix.data.Instance

/** A registration this old is sent again at the next `hello` (design section 10, "Registration"). */
const val REGISTRATION_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1_000

/** What a connection to a daemon sends about this phone's push token, if anything. */
enum class RegistrationStep {
    /** `push_register{platform:"android", token}`. */
    REGISTER,

    /** `push_unregister`: notifications cannot show, so no push may come (onboarding gotcha 10). */
    UNREGISTER,

    NONE,
}

/**
 * What [record]'s connection sends now, at [nowMs] in Unix milliseconds (design section 10, "Registration"):
 * a daemon that pushes through FCM, whose Notifications switch is on, while its notifications can show
 * ([canShow]: `POST_NOTIFICATIONS` granted, the app's notifications on, the instance's channel not blocked),
 * is registered when it never was since the last token, or that was 7 days ago or more, or the clock says it
 * was later than now; and one registered whose notifications cannot show is unregistered, so FCM never
 * counts a push that shows nothing against the app.
 */
fun registrationStep(
    record: Instance,
    canShow: Boolean,
    nowMs: Long,
): RegistrationStep {
    require(nowMs >= 0L) { "the time is $nowMs" }
    val registeredAt = record.fcmRegisteredAt
    val wanted = pushWanted(record, canShow)
    return when {
        wanted && (registeredAt == null || nowMs - registeredAt !in 0 until REGISTRATION_MAX_AGE_MS) -> {
            RegistrationStep.REGISTER
        }

        !wanted && registeredAt != null -> {
            RegistrationStep.UNREGISTER
        }

        else -> {
            RegistrationStep.NONE
        }
    }
}

/**
 * Whether [record]'s daemon is to push to this phone: it pushes through FCM, its Notifications switch is on, and
 * its notifications can show ([canShow]). One that is not, and was registered, is owed a `push_unregister`.
 */
fun pushWanted(
    record: Instance,
    canShow: Boolean,
): Boolean = record.notificationsEnabled && canShow && record.pushReady
