package dev.bakanov.hookrelay.webhook;

import java.time.Duration;

final class DeliveryRetryPolicy {

    private DeliveryRetryPolicy() {
    }

    static boolean shouldRetry(Integer httpStatus, int attemptCount) {
        return attemptCount < 3 && (httpStatus == null || httpStatus == 408 || httpStatus == 429
                || (httpStatus >= 500 && httpStatus <= 599));
    }

    static Duration delayAfter(int attemptCount) {
        return Duration.ofSeconds(5L * (1L << (attemptCount - 1)));
    }
}
