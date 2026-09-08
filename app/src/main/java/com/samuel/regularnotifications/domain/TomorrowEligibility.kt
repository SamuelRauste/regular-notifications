package com.samuel.regularnotifications.domain

/** The single product rule used by both persistence reconciliation and notifications. */
fun supportsTomorrow(intervalDays: Int): Boolean = intervalDays >= 2
