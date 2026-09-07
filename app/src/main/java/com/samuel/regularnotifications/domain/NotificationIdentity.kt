package com.samuel.regularnotifications.domain

enum class NotificationKind(val uriSegment: String, val salt: Long) {
    DUE("due", 0x13579BDF2468ACE0L),
    TOMORROW("tomorrow", 0x0ECA8642FDB97531L),
}

object NotificationIdentity {
    fun notificationId(reminderId: Long, kind: NotificationKind): Int =
        mixedInt(reminderId xor kind.salt)

    fun alarmRequestCode(reminderId: Long, kind: NotificationKind): Int =
        notificationId(reminderId, kind)

    fun pendingIntentData(reminderId: Long, kind: NotificationKind): String =
        "regular-notifications://${kind.uriSegment}/$reminderId"

    private fun mixedInt(value: Long): Int {
        var mixed = value
        mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
        mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
        mixed = mixed xor (mixed ushr 33)
        return (mixed.toInt() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
