/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.worker;

import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.temporal.context.ContextPropagator;
import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.worker.PollerOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class FactoryOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(FactoryOptions o) {
    return new Builder(o);
  }

  public static class Builder {
    private boolean disableStickyExecution;
    private int stickyDecisionScheduleToStartTimeoutInSeconds = 5;
    private int cacheMaximumSize = 600;
    private int maxWorkflowThreadCount = 600;
    private PollerOptions stickyWorkflowPollerOptions;
    private Scope metricScope;
    private List<ContextPropagator> contextPropagators;

    private Builder() {}

    private Builder(FactoryOptions o) {
      if (o == null) {
        return;
      }
      this.disableStickyExecution = o.disableStickyExecution;
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          o.stickyDecisionScheduleToStartTimeoutInSeconds;
      this.cacheMaximumSize = o.cacheMaximumSize;
      this.maxWorkflowThreadCount = o.maxWorkflowThreadCount;
      this.stickyWorkflowPollerOptions = o.stickyWorkflowPollerOptions;
      this.metricScope = o.metricsScope;
      this.contextPropagators = o.contextPropagators;
    }

    /**
     * When set to false it will create an affinity between the worker and the workflow run it's
     * processing. Workers will cache workflows and will handle all decisions for that workflow
     * instance until it's complete or evicted from the cache. Default value is false.
     */
    public Builder setDisableStickyExecution(boolean disableStickyExecution) {
      this.disableStickyExecution = disableStickyExecution;
      return this;
    }

    /**
     * When Sticky execution is enabled this will set the maximum allowed number of workflows
     * cached. This cache is shared by all workers created by the Factory. Default value is 600
     */
    public Builder setCacheMaximumSize(int cacheMaximumSize) {
      this.cacheMaximumSize = cacheMaximumSize;
      return this;
    }

    /**
     * Maximum number of threads available for workflow execution across all workers created by the
     * Factory.
     */
    public Builder setMaxWorkflowThreadCount(int maxWorkflowThreadCount) {
      this.maxWorkflowThreadCount = maxWorkflowThreadCount;
      return this;
    }

    /**
     * Timeout for sticky workflow decision to be picked up by the host assigned to it. Once it
     * times out then it can be picked up by any worker. Default value is 5 seconds.
     */
    public Builder setStickyDecisionScheduleToStartTimeoutInSeconds(
        int stickyDecisionScheduleToStartTimeoutInSeconds) {
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          stickyDecisionScheduleToStartTimeoutInSeconds;
      return this;
    }

    /**
     * PollerOptions for poller responsible for polling for decisions for workflows cached by all
     * workers created by this factory.
     */
    public Builder setStickyWorkflowPollerOptions(PollerOptions stickyWorkflowPollerOptions) {
      this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
      return this;
    }

    public Builder setMetricScope(Scope metricScope) {
      this.metricScope = metricScope;
      return this;
    }

    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public FactoryOptions build() {
      return new FactoryOptions(
          disableStickyExecution,
          cacheMaximumSize,
          maxWorkflowThreadCount,
          stickyDecisionScheduleToStartTimeoutInSeconds,
          stickyWorkflowPollerOptions,
          metricScope,
          contextPropagators);
    }
  }

  private final boolean disableStickyExecution;
  private final int cacheMaximumSize;
  private final int maxWorkflowThreadCount;
  private final int stickyDecisionScheduleToStartTimeoutInSeconds;
  private final PollerOptions stickyWorkflowPollerOptions;
  private final Scope metricsScope;
  private List<ContextPropagator> contextPropagators;

  private FactoryOptions(
      boolean disableStickyExecution,
      int cacheMaximumSize,
      int maxWorkflowThreadCount,
      int stickyDecisionScheduleToStartTimeoutInSeconds,
      PollerOptions stickyWorkflowPollerOptions,
      Scope metricsScope,
      List<ContextPropagator> contextPropagators) {
    Preconditions.checkArgument(cacheMaximumSize > 0, "cacheMaximumSize should be greater than 0");
    Preconditions.checkArgument(
        maxWorkflowThreadCount > 0, "maxWorkflowThreadCount should be greater than 0");
    Preconditions.checkArgument(
        stickyDecisionScheduleToStartTimeoutInSeconds > 0,
        "stickyDecisionScheduleToStartTimeoutInSeconds should be greater than 0");

    this.disableStickyExecution = disableStickyExecution;
    this.cacheMaximumSize = cacheMaximumSize;
    this.maxWorkflowThreadCount = maxWorkflowThreadCount;
    this.stickyDecisionScheduleToStartTimeoutInSeconds =
        stickyDecisionScheduleToStartTimeoutInSeconds;

    if (stickyWorkflowPollerOptions == null) {
      this.stickyWorkflowPollerOptions =
          PollerOptions.newBuilder()
              .setPollBackoffInitialInterval(Duration.ofMillis(200))
              .setPollBackoffMaximumInterval(Duration.ofSeconds(20))
              .setPollThreadCount(1)
              .build();
    } else {
      this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
    }

    if (metricsScope == null) {
      this.metricsScope = NoopScope.getInstance();
    } else {
      this.metricsScope = metricsScope;
    }

    if (contextPropagators != null) {
      this.contextPropagators = contextPropagators;
    } else {
      this.contextPropagators = new ArrayList<>();
    }
  }

  public boolean isDisableStickyExecution() {
    return disableStickyExecution;
  }

  public int getCacheMaximumSize() {
    return cacheMaximumSize;
  }

  public int getMaxWorkflowThreadCount() {
    return maxWorkflowThreadCount;
  }

  public int getStickyDecisionScheduleToStartTimeoutInSeconds() {
    return stickyDecisionScheduleToStartTimeoutInSeconds;
  }

  public PollerOptions getStickyWorkflowPollerOptions() {
    return stickyWorkflowPollerOptions;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }
}
