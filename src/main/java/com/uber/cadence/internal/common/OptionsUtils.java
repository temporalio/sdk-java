package com.uber.cadence.internal.common;

import com.google.common.base.Defaults;

import java.time.Duration;

public final class OptionsUtils {

    public static final Duration DEFAULT_TASK_START_TO_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Merges value from annotation and option.
     * Option value takes precedence.
     */
    public static <G> G merge(G annotation, G options, Class<G> type) {
        G defaultValue = Defaults.defaultValue(type);
        if (defaultValue == null) {
            if (options != null) {
                return options;
            }
        }  else if (!defaultValue.equals(options)) {
            return options;
        }
        if (type.equals(String.class)) {
            return ((String) annotation).isEmpty() ? null : annotation;
        }
        return annotation;
    }

    /**
     * Merges value from annotation in seconds with option value
     * as Duration. Option value takes precedence.
     */
    public static Duration merge(long aSeconds, Duration o) {
        if (o != null) {
            return o;
        }
        return aSeconds == 0 ? null : Duration.ofSeconds(aSeconds);
    }

    /**
     * Prohibits instantiation.
     */
    private OptionsUtils() {
    }
}
