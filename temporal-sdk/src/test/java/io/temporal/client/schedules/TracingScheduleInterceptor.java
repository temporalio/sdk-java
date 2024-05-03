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

package io.temporal.client.schedules;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptorBase;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ScheduleClientInterceptor that just logs the calls it intercepts. The implementation is taken
 * from TracingWorkerInterceptor, with very minor tweaks.
 */
public class TracingScheduleInterceptor implements ScheduleClientInterceptor {
  private static final Logger log = LoggerFactory.getLogger(TracingWorkerInterceptor.class);

  private final FilteredTrace trace;
  private List<String> expected;

  public TracingScheduleInterceptor(FilteredTrace trace) {
    this.trace = trace;
  }

  public String getTrace() {
    return String.join("\n", trace.getImpl());
  }

  public void setExpected(String... expected) {
    this.expected = Arrays.asList(expected);
  }

  public void assertExpected() {
    if (expected != null) {
      List<String> traceElements = trace.getImpl();
      if (traceElements.isEmpty()) {
        fail("Expected to find traces, but found none");
      }

      for (int i = 0; i < traceElements.size(); i++) {
        String t = traceElements.get(i);
        String expectedRegExp;
        if (expected.size() <= i) {
          expectedRegExp = "";
        } else {
          expectedRegExp = expected.get(i);
        }
        assertTrue(
            t
                + " doesn't match "
                + expectedRegExp
                + ": \n expected=\n"
                + String.join("\n", expected)
                + "\n actual=\n"
                + String.join("\n", traceElements)
                + "\n",
            t.matches(expectedRegExp));
      }
    }
  }

  @Override
  public ScheduleClientCallsInterceptor scheduleClientCallsInterceptor(
      ScheduleClientCallsInterceptor next) {
    return new TracingScheduleCallsInterceptor(trace, next);
  }

  public static class TracingScheduleCallsInterceptor extends ScheduleClientCallsInterceptorBase {
    private final FilteredTrace trace;
    private final ScheduleClientCallsInterceptor next;

    public TracingScheduleCallsInterceptor(
        FilteredTrace trace, ScheduleClientCallsInterceptor next) {
      super(next);
      this.trace = trace;
      this.next = next;
    }

    @Override
    public void createSchedule(CreateScheduleInput input) {
      trace.add("createSchedule: " + input.getId());
      next.createSchedule(input);
    }

    @Override
    public ListScheduleOutput listSchedules(ListSchedulesInput input) {
      trace.add("listSchedules");
      return next.listSchedules(input);
    }

    @Override
    public void backfillSchedule(BackfillScheduleInput input) {
      trace.add("backfillSchedule: " + input.getScheduleId());
      next.backfillSchedule(input);
    }

    @Override
    public void deleteSchedule(DeleteScheduleInput input) {
      trace.add("deleteSchedule: " + input.getScheduleId());
      next.deleteSchedule(input);
    }

    @Override
    public DescribeScheduleOutput describeSchedule(DescribeScheduleInput input) {
      trace.add("describeSchedule: " + input.getScheduleId());
      return next.describeSchedule(input);
    }

    @Override
    public void pauseSchedule(PauseScheduleInput input) {
      trace.add("pauseSchedule: " + input.getScheduleId());
      next.pauseSchedule(input);
    }

    @Override
    public void triggerSchedule(TriggerScheduleInput input) {
      trace.add("triggerSchedule: " + input.getScheduleId());
      next.triggerSchedule(input);
    }

    @Override
    public void unpauseSchedule(UnpauseScheduleInput input) {
      trace.add("unpauseSchedule: " + input.getScheduleId());
      next.unpauseSchedule(input);
    }

    @Override
    public void updateSchedule(UpdateScheduleInput input) {
      trace.add("updateSchedule: " + input.getDescription().getId());
      next.updateSchedule(input);
    }
  }

  public static class FilteredTrace {

    private final List<String> impl = Collections.synchronizedList(new ArrayList<>());

    public boolean add(String s) {
      log.trace("FilteredTrace isReplaying=" + WorkflowUnsafe.isReplaying());
      if (!WorkflowUnsafe.isReplaying()) {
        return impl.add(s);
      }
      return true;
    }

    List<String> getImpl() {
      return impl;
    }
  }
}
