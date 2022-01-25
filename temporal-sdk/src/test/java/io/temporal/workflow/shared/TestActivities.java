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

import com.google.common.base.Preconditions;
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
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
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
    @ActivityMethod
    String execute(String input);
  }

  @ActivityInterface
  public interface NoArgsReturnsStringActivity {
    @ActivityMethod
    String execute();
  }

  @ActivityInterface
  public interface TestActivity3 {
    @ActivityMethod
    int execute(int input);
  }

  @ActivityInterface
  public interface TestActivity4 {
    @ActivityMethod
    void execute(String arg1, boolean arg2);
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    Map<String, Duration> activity1();

    @ActivityMethod
    Map<String, Duration> activity2();
  }

  @ActivityInterface
  public interface TestLocalActivity {

    @ActivityMethod
    Map<String, Duration> localActivity1();

    @ActivityMethod
    Map<String, Duration> localActivity2();
  }

  @ActivityInterface
  public interface VariousTestActivities {

    String sleepActivity(long milliseconds, int input);

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

  @ActivityInterface
  public interface CompletionClientActivities {
    String activityWithDelay(long milliseconds, boolean heartbeatMoreThanOnce);

    String activity1(String a1);
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
      return new HashMap<String, Duration>() {
        {
          put("HeartbeatTimeout", info.getHeartbeatTimeout());
          put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
          put("StartToCloseTimeout", info.getStartToCloseTimeout());
        }
      };
    }

    @Override
    public Map<String, Duration> activity2() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      return new HashMap<String, Duration>() {
        {
          put("HeartbeatTimeout", info.getHeartbeatTimeout());
          put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
          put("StartToCloseTimeout", info.getStartToCloseTimeout());
        }
      };
    }
  }

  public static class TestLocalActivityImpl implements TestLocalActivity {

    @Override
    public Map<String, Duration> localActivity1() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      return new HashMap<String, Duration>() {
        {
          put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
          put("StartToCloseTimeout", info.getStartToCloseTimeout());
        }
      };
    }

    @Override
    public Map<String, Duration> localActivity2() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      return new HashMap<String, Duration>() {
        {
          put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout());
          put("StartToCloseTimeout", info.getStartToCloseTimeout());
        }
      };
    }
  }

  public static class TestActivitiesImpl implements VariousTestActivities {

    public final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
    public final List<String> procResult = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger heartbeatCounter = new AtomicInteger();
    public final AtomicInteger applicationFailureCounter = new AtomicInteger();
    int lastAttempt;

    public void assertInvocations(String... expected) {
      assertEquals(Arrays.asList(expected), invocations);
    }

    @Override
    public String sleepActivity(long milliseconds, int input) {
      try {
        Thread.sleep(milliseconds);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Activity.wrap(e);
      }
      invocations.add("sleepActivity");
      return "sleepActivity" + input;
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
      invocations.add("activity4");
      return a1 + a2 + a3 + a4;
    }

    @Override
    public String activity5(String a1, int a2, int a3, int a4, int a5) {
      invocations.add("activity5");
      return a1 + a2 + a3 + a4 + a5;
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

  public static class CompletionClientActivitiesImpl
      implements CompletionClientActivities, Closeable {
    public final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
    private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(0, 100, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public ActivityCompletionClient completionClient;

    public void setCompletionClient(ActivityCompletionClient completionClient) {
      this.completionClient = completionClient;
    }

    public void assertInvocations(String... expected) {
      assertEquals(Arrays.asList(expected), invocations);
    }

    @Override
    public String activity1(String a1) {
      Preconditions.checkNotNull(completionClient, "completionClient");
      byte[] taskToken = Activity.getExecutionContext().getInfo().getTaskToken();
      executor.execute(
          () -> {
            invocations.add("activity1");
            completionClient.complete(taskToken, a1);
          });
      Activity.getExecutionContext().doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activityWithDelay(long delay, boolean heartbeatMoreThanOnce) {
      Preconditions.checkNotNull(completionClient, "completionClient");
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
              Thread.currentThread().interrupt();
            } catch (ActivityNotExistsException | ActivityCanceledException e) {
              try {
                Thread.sleep(500);
              } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
              }
              completionClient.reportCancellation(taskToken, null);
            }
          });
      ctx.doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public void close() throws IOException {
      executor.shutdownNow();
    }
  }
}
