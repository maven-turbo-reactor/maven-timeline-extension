package com.github.seregamorph.maven.timeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * @author Sergey Chernov
 */
public final class TimeFormatUtils {

    public static BigDecimal toSeconds(Duration duration) {
        return toSeconds(duration.toMillis());
    }

    public static BigDecimal toSeconds(long durationMillis) {
        return BigDecimal.valueOf(durationMillis).divide(BigDecimal.valueOf(1000L), 3, RoundingMode.HALF_UP);
    }

    private TimeFormatUtils() {
    }
}
