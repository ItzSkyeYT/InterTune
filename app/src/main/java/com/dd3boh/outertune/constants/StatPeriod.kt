package com.dd3boh.outertune.constants

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

enum class StatPeriod {
    `1_WEEK`, `1_MONTH`, `3_MONTH`, `6_MONTH`, `1_YEAR`, ALL;

    fun toTimeMillis(): Long =
        when (this) {
            `1_WEEK` -> LocalDateTime.now().minusWeeks(1).toInstant(ZoneOffset.UTC).toEpochMilli()
            `1_MONTH` -> LocalDateTime.now().minusMonths(1).toInstant(ZoneOffset.UTC).toEpochMilli()
            `3_MONTH` -> LocalDateTime.now().minusMonths(3).toInstant(ZoneOffset.UTC).toEpochMilli()
            `6_MONTH` -> LocalDateTime.now().minusMonths(6).toInstant(ZoneOffset.UTC).toEpochMilli()
            `1_YEAR` -> LocalDateTime.now().minusMonths(12).toInstant(ZoneOffset.UTC).toEpochMilli()
            ALL -> 0
        }

    /**
     * The first instant of the period as true UTC, for the listen log, which stores real instants.
     * [toTimeMillis] is for the event table, which stores the wall clock as if it were UTC. 0 for All.
     */
    fun startMillis(now: ZonedDateTime = ZonedDateTime.now()): Long =
        when (this) {
            `1_WEEK` -> now.minusWeeks(1)
            `1_MONTH` -> now.minusMonths(1)
            `3_MONTH` -> now.minusMonths(3)
            `6_MONTH` -> now.minusMonths(6)
            `1_YEAR` -> now.minusMonths(12)
            ALL -> null
        }?.toInstant()?.toEpochMilli() ?: 0L

    fun toLocalDateTime(): LocalDateTime =
        when (this) {
            `1_WEEK` -> LocalDateTime.now().minusWeeks(1)
            `1_MONTH` -> LocalDateTime.now().minusMonths(1)
            `3_MONTH` -> LocalDateTime.now().minusMonths(3)
            `6_MONTH` -> LocalDateTime.now().minusMonths(6)
            `1_YEAR` -> LocalDateTime.now().minusMonths(12)
            ALL -> LocalDateTime.now().minusMonths(2400)
        }
}