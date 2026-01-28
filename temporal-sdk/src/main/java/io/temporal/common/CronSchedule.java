package io.temporal.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CronSchedule - Optional cron schedule for workflow. If a cron schedule is specified, the workflow
 * will run as a cron based on the schedule. The scheduling will be based on UTC time. Schedule for
 * next run only happen after the current run is completed/failed/timeout. If a RetryPolicy is also
 * supplied, and the workflow failed or timeout, the workflow will be retried based on the retry
 * policy. While the workflow is retrying, it won't schedule its next run. If next schedule is due
 * while workflow is running (or retrying), then it will skip that schedule. Cron workflow will not
 * stop until it is terminated or canceled.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CronSchedule {
  String value();
}
