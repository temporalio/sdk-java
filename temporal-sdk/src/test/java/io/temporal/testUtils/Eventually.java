package io.temporal.testUtils;

import java.time.Duration;
import java.time.Instant;

public class Eventually {

    /**
     * @param maxDuration the duration during which the command will be retried after it fails exceptionally
     * @param command       the logic that fails exceptionally while its assertions are not successful
     */
    public static void assertEventually(Duration maxDuration, Runnable command) {
        final Instant start = Instant.now();
        final Instant max = start.plus(maxDuration);

        boolean failed;
        do {
            try {
                command.run();
                failed = false;
            } catch (Throwable t) {
                failed = true;
                if (Instant.now().isBefore(max)) {
                    // Try again after a short nap
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                } else {
                    // Max duration has exceeded, it took too long to become consistent
                    throw t;
                }
            }
        } while (failed);
    }

}
