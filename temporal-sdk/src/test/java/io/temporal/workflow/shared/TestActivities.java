/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.workflow.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.common.MethodRetry;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestActivities {

  @ActivityInterface
  public interface NoArgsActivity {
    @ActivityMethod
    void execute();
  }

  @ActivityInterface
  public interface TestActivity1 {
    String execute(String input);
  }

  @ActivityInterface
  public interface TestActivity2 {
    String execute();
  }

  @ActivityInterface
  public interface TestActivity3 {
    int execute(int input);
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    Map<String, Duration> activity1();

    @ActivityMethod
    Map<String, Duration> activity2();
  }

  @ActivityInterface
  public interface VariousTestActivities {

    String sleepActivity(long milliseconds, int input);

    String activityWithDelay(long milliseconds, boolean heartbeatMoreThanOnce);

    String activity();

    @ActivityMethod(name = "customActivity1")
    int activity1(int input);

    String activity2(String a1, int a2);

    String activity3(String a1, int a2, int a3);

    String activity4(String a1, int a2, int a3, int a4);

    String activity5(String a1, int a2, int a3, int a4, int a5);

    String activity6(String a1, int a2, int a3, int a4, int a5, int a6);

    void proc();

    void proc1(String input);

    void proc2(String a1, int a2);

    void proc3(String a1, int a2, int a3);

    void proc4(String a1, int a2, int a3, int a4);

    void proc5(String a1, int a2, int a3, int a4, int a5);

    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);

    void heartbeatAndThrowIO();

    void throwIO();

    void throwApplicationFailureThreeTimes();

    void neverComplete();

    @MethodRetry(initialIntervalSeconds = 1, maximumIntervalSeconds = 1, maximumAttempts = 3)
    void throwIOAnnotated();

    List<UUID> activityUUIDList(List<UUID> arg);
  }

  /** IMPLEMENTATIONS * */
  public static class AngryChildActivityImpl implements NoArgsActivity {

    private long invocationCount;

    @Override
    public void execute() {
      invocationCount++;
    }

    public long getInvocationCount() {
      return invocationCount;
    }
  }

  public static class TestActivityImpl implements TestActivity {

    @Override
    public Map<String, Duration> activity1() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      Hashtable<String, Duration> result =
          new Hashtable<String, Duration>() {
            {
              put("HeartbeatTimeout", info.getHeartbeatTimeout());
              put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
              put("StartToCloseTimeout", info.getStartToCloseTimeout());
            }
          };
      return result;
    }

    @Override
    public Map<String, Duration> activity2() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      Hashtable<String, Duration> result =
          new Hashtable<String, Duration>() {
            {
              put("HeartbeatTimeout", info.getHeartbeatTimeout());
              put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
              put("StartToCloseTimeout", info.getStartToCloseTimeout());
            }
          };
      return result;
    }
  }

  public static class TestActivitiesImpl implements VariousTestActivities {

    public final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
    public final List<String> procResult = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger heartbeatCounter = new AtomicInteger();
    public final AtomicInteger applicationFailureCounter = new AtomicInteger();
    private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(0, 100, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public ActivityCompletionClient completionClient;
    int lastAttempt;

    public void setCompletionClient(ActivityCompletionClient completionClient) {
      this.completionClient = completionClient;
    }

    public void close() throws InterruptedException {
      executor.shutdownNow();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }

    public void assertInvocations(String... expected) {
      assertEquals(Arrays.asList(expected), invocations);
    }

    @Override
    public String sleepActivity(long milliseconds, int input) {
      try {
        Thread.sleep(milliseconds);
      } catch (InterruptedException e) {
        throw Activity.wrap(new RuntimeException("interrupted", new Throwable("simulated")));
      }
      invocations.add("sleepActivity");
      return "sleepActivity" + input;
    }

    @Override
    public String activityWithDelay(long delay, boolean heartbeatMoreThanOnce) {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      byte[] taskToken = ctx.getInfo().getTaskToken();
      executor.execute(
          () -> {
            invocations.add("activityWithDelay");
            long start = System.currentTimeMillis();
            try {
              int count = 0;
              while (System.currentTimeMillis() - start < delay) {
                if (heartbeatMoreThanOnce || count == 0) {
                  completionClient.heartbeat(taskToken, "heartbeatValue");
                }
                count++;
                Thread.sleep(100);
              }
              completionClient.complete(taskToken, "activity");
            } catch (InterruptedException e) {
            } catch (ActivityNotExistsException | ActivityCanceledException e) {
              try {
                Thread.sleep(500);
              } catch (InterruptedException interruptedException) {
                // noop
              }
              completionClient.reportCancellation(taskToken, null);
            }
          });
      ctx.doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity() {
      invocations.add("activity");
      return "activity";
    }

    @Override
    public int activity1(int a1) {
      invocations.add("activity1");
      return a1;
    }

    @Override
    public String activity2(String a1, int a2) {
      invocations.add("activity2");
      return a1 + a2;
    }

    @Override
    public String activity3(String a1, int a2, int a3) {
      invocations.add("activity3");
      return a1 + a2 + a3;
    }

    @Override
    public String activity4(String a1, int a2, int a3, int a4) {
      byte[] taskToken = Activity.getExecutionContext().getInfo().getTaskToken();
      executor.execute(
          () -> {
            invocations.add("activity4");
            completionClient.complete(taskToken, a1 + a2 + a3 + a4);
          });
      Activity.getExecutionContext().doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity5(String a1, int a2, int a3, int a4, int a5) {
      ActivityInfo activityInfo = Activity.getExecutionContext().getInfo();
      String workflowId = activityInfo.getWorkflowId();
      String id = activityInfo.getActivityId();
      executor.execute(
          () -> {
            invocations.add("activity5");
            completionClient.complete(workflowId, Optional.empty(), id, a1 + a2 + a3 + a4 + a5);
          });
      Activity.getExecutionContext().doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity6(String a1, int a2, int a3, int a4, int a5, int a6) {
      invocations.add("activity6");
      return a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public void proc() {
      invocations.add("proc");
      procResult.add("proc");
    }

    @Override
    public void proc1(String a1) {
      invocations.add("proc1");
      procResult.add(a1);
    }

    @Override
    public void proc2(String a1, int a2) {
      invocations.add("proc2");
      procResult.add(a1 + a2);
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      invocations.add("proc3");
      procResult.add(a1 + a2 + a3);
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      invocations.add("proc4");
      procResult.add(a1 + a2 + a3 + a4);
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      invocations.add("proc5");
      procResult.add(a1 + a2 + a3 + a4 + a5);
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      invocations.add("proc6");
      procResult.add(a1 + a2 + a3 + a4 + a5 + a6);
    }

    @Override
    public void heartbeatAndThrowIO() {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      ActivityInfo info = ctx.getInfo();
      assertEquals(info.getAttempt(), heartbeatCounter.get() + 1);
      invocations.add("throwIO");
      Optional<Integer> heartbeatDetails = ctx.getHeartbeatDetails(int.class);
      assertEquals(heartbeatCounter.get(), (int) heartbeatDetails.orElse(0));
      ctx.heartbeat(heartbeatCounter.incrementAndGet());
      assertEquals(heartbeatCounter.get(), (int) ctx.getHeartbeatDetails(int.class).get());
      try {
        throw new IOException("simulated IO problem");
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }

    @Override
    public void throwIO() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      assertEquals(SDKTestWorkflowRule.NAMESPACE, info.getWorkflowNamespace());
      assertNotNull(info.getWorkflowId());
      assertNotNull(info.getRunId());
      assertFalse(info.getWorkflowId().isEmpty());
      assertFalse(info.getRunId().isEmpty());
      lastAttempt = info.getAttempt();
      invocations.add("throwIO");
      try {
        throw new IOException("simulated IO problem", new Throwable("test throwable wrapping"));
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }

    @Override
    public void throwApplicationFailureThreeTimes() {
      ApplicationFailure failure =
          ApplicationFailure.newNonRetryableFailure("simulated", "simulatedType");
      failure.setNonRetryable(applicationFailureCounter.incrementAndGet() > 2);
      throw failure;
    }

    @Override
    public void neverComplete() {
      invocations.add("neverComplete");
      Activity.getExecutionContext().doNotCompleteOnReturn(); // Simulate activity timeout
    }

    @Override
    public void throwIOAnnotated() {
      invocations.add("throwIOAnnotated");
      try {
        throw new IOException("simulated IO problem");
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }

    @Override
    public List<UUID> activityUUIDList(List<UUID> arg) {
      return arg;
    }

    public int getLastAttempt() {
      return lastAttempt;
    }
  }
}
