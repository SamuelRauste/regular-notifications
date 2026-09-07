package com.samuel.regularnotifications.domain

enum class IntervalUnit {
    MINUTES,
    HOURS,
    DAYS,
    WEEKS,
    ;

    companion object {
        fun fromPersisted(value: String): IntervalUnit =
            entries.firstOrNull { it.name == value }
                ?: error("Unknown interval unit: $value")
    }
}
