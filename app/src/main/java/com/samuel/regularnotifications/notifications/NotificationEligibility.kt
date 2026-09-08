package com.samuel.regularnotifications.notifications

import com.samuel.regularnotifications.domain.supportsTomorrow

fun supportsTomorrowNotification(intervalDays: Int): Boolean = supportsTomorrow(intervalDays)
