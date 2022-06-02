/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
