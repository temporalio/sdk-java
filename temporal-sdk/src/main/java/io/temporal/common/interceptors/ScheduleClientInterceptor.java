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

package io.temporal.common.interceptors;

import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.common.Experimental;

/**
 * Intercepts calls to the {@link ScheduleClient} and {@link ScheduleHandle} related to the
 * lifecycle of a Schedule.
 */
@Experimental
public interface ScheduleClientInterceptor {

  /**
   * Called once during creation of ScheduleClient to create a chain of ScheduleClient Interceptors
   *
   * @param next next schedule client interceptor in the chain of interceptors
   * @return new interceptor that should decorate calls to {@code next}
   */
  ScheduleClientCallsInterceptor scheduleClientCallsInterceptor(
      ScheduleClientCallsInterceptor next);
}
