package com.github.seregamorph.maven.timeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * @author Sergey Chernov
 */
public final class TimeFormatUtils {

    public static BigDecimal toSeconds(Duration duration) {
        return toSeconds(duration.toNanos());
    }

    public static BigDecimal toSeconds(long durationNanos) {
        return BigDecimal.valueOf(durationNanos).divide(BigDecimal.valueOf(1000_000_000L), 6, RoundingMode.HALF_UP);
    }

    private TimeFormatUtils() {
    }
}
