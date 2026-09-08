package com.samuel.regularnotifications.domain

enum class NotificationKind(val uriSegment: String, val notificationId: Int) {
    DUE("due", 1),
    TOMORROW("tomorrow", 2),
}

/** The pair Android uses to replace or cancel one logical notification. */
data class NotificationTarget(
    val tag: String,
    val notificationId: Int,
)

object NotificationIdentity {
    /**
     * The full reminder ID lives in the tag. The integer ID is only a stable
     * kind-local value, so two 64-bit reminder IDs cannot collide at the
     * NotificationManager level.
     */
    fun target(reminderId: Long, kind: NotificationKind): NotificationTarget =
        NotificationTarget(
            tag = notificationTag(reminderId, kind),
            notificationId = kind.notificationId,
        )

    fun notificationTag(reminderId: Long, kind: NotificationKind): String =
        "regular-notifications-${kind.uriSegment}-$reminderId"

    fun notificationId(reminderId: Long, kind: NotificationKind): Int =
        target(reminderId, kind).notificationId

    fun alarmRequestCode(reminderId: Long, kind: NotificationKind): Int =
        stableInt("alarm/${pendingIntentData(reminderId, kind)}")

    fun pendingIntentData(reminderId: Long, kind: NotificationKind): String =
        "regular-notifications://${kind.uriSegment}/$reminderId"

    fun actionPendingIntentData(
        reminderId: Long,
        kind: NotificationKind,
        actionSegment: String,
    ): String =
        "regular-notifications://action/${kind.uriSegment}/$actionSegment/$reminderId"

    fun actionPendingIntentRequestCode(
        reminderId: Long,
        kind: NotificationKind,
        actionSegment: String,
    ): Int = stableInt("action/${actionPendingIntentData(reminderId, kind, actionSegment)}")

    private fun stableInt(value: String): Int =
        (value.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
}
