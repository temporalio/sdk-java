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

package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkerFactoryOptions;
import javax.annotation.Nullable;

public class WorkerFactoryOptionsTemplate {
  private final @Nullable Tracer tracer;
  private final @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> customizer;

  public WorkerFactoryOptionsTemplate(
      @Nullable Tracer tracer,
      @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> customizer) {
    this.tracer = tracer;
    this.customizer = customizer;
  }

  public WorkerFactoryOptions createWorkerFactoryOptions() {
    WorkerFactoryOptions.Builder options = WorkerFactoryOptions.newBuilder();
    if (tracer != null) {
      OpenTracingWorkerInterceptor openTracingClientInterceptor =
          new OpenTracingWorkerInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      options.setWorkerInterceptors(openTracingClientInterceptor);
    }

    if (customizer != null) {
      options = customizer.customize(options);
    }

    return options.build();
  }
}
