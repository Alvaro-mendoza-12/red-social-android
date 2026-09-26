package conectate.com.presentation.analytics

import conectate.com.data.model.CreatorAnalyticsRange
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class CreatorAnalyticsPeriod(val label: String, val days: Long) {
    SEVEN_DAYS("7 días", 7),
    THIRTY_DAYS("30 días", 30),
    NINETY_DAYS("90 días", 90),
    YEAR("1 año", 365),
}

fun CreatorAnalyticsPeriod.rangeEndingAt(now: Instant): CreatorAnalyticsRange =
    CreatorAnalyticsRange(
        from = now.minus(days, ChronoUnit.DAYS).toString(),
        to = now.toString(),
    )
