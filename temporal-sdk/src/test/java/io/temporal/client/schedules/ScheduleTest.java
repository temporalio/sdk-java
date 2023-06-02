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

import static org.junit.Assume.assumeTrue;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.EncodedValues;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ScheduleTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ScheduleTest.QuickWorkflowImpl.class)
          .build();

  private ScheduleClient createScheduleClient() {
    return new ScheduleClientImpl(
        testWorkflowRule.getWorkflowServiceStubs(),
        ScheduleClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setIdentity(testWorkflowRule.getWorkflowClient().getOptions().getIdentity())
            .setDataConverter(testWorkflowRule.getWorkflowClient().getOptions().getDataConverter())
            .build());
  }

  private void waitForActions(ScheduleHandle handle, long actions) {
    while (true) {
      if (handle.describe().getInfo().getNumActions() >= actions) {
        return;
      }
      testWorkflowRule.sleep(Duration.ofSeconds(1));
    }
  }

  private Schedule.Builder createTestSchedule() {
    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId("test-schedule-id")
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(Collections.singletonMap("memokey1", "memoval1"))
            .build();
    return Schedule.newBuilder(
        ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType("TestWorkflow1")
            .setArgs("arg")
            .setOptions(wfOptions)
            .build(),
        ScheduleSpec.newBuilder()
            .setIntervals(Arrays.asList(new ScheduleIntervalSpec(Duration.ofSeconds(1))))
            .build());
  }

  @Before
  public void checkRealServer() {
    assumeTrue("skipping for test server", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void createSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    Assert.assertEquals(scheduleId, description.getId());
    // Try to create a schedule that already exists
    Assert.assertThrows(
        ScheduleAlreadyRunningException.class,
        () -> client.createSchedule(scheduleId, schedule, options));
    // Clean up schedule
    handle.delete();
    // Describe a deleted schedule
    try {
      handle.describe();
      Assert.fail();
    } catch (Exception e) {
    }
    // Create a handle to a non-existent schedule, creating the handle should not throw
    // but any operations on it should.
    try {
      ScheduleHandle badHandle = client.getHandle(UUID.randomUUID().toString());
      badHandle.delete();
      Assert.fail();
    } catch (Exception e) {
    }
  }

  @Test
  public void pauseUnpauseSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    // Verify the initial state of the schedule
    Assert.assertEquals("", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());
    // Pause the schedule
    handle.pause();
    description = handle.describe();
    Assert.assertEquals("Paused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.unpause();
    description = handle.describe();
    Assert.assertEquals("Unpaused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());

    handle.pause("pause via test");
    description = handle.describe();
    Assert.assertEquals("pause via test", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.pause("");
    description = handle.describe();
    Assert.assertEquals("Paused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.unpause("unpause via test");
    description = handle.describe();
    Assert.assertEquals("unpause via test", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());

    handle.unpause("");
    description = handle.describe();
    Assert.assertEquals("Unpaused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void limitedActionSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setState(
                ScheduleState.newBuilder().setLimitedAction(true).setRemainingActions(3).build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 3);
    // Verify all 3 actions have run
    ScheduleDescription description = handle.describe();
    Assert.assertEquals(0, description.getSchedule().getState().getRemainingActions());
    Assert.assertEquals(true, description.getSchedule().getState().isLimitedAction());
    Assert.assertEquals(
        3,
        description.getInfo().getRecentActions().size()
            + description.getInfo().getRunningActions().size());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void triggerSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().setTriggerImmediately(true).build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE)
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(true).build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 1);
    Assert.assertEquals(1, handle.describe().getInfo().getNumActions());
    // Trigger the schedule and verify a new action was run
    handle.trigger();
    waitForActions(handle, 2);
    Assert.assertEquals(2, handle.describe().getInfo().getNumActions());
    // Trigger the schedule and verify a new action was run
    handle.trigger();
    waitForActions(handle, 3);
    Assert.assertEquals(3, handle.describe().getInfo().getNumActions());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void backfillSchedules() {
    // assumeTrue("skipping for test server", SDKTestWorkflowRule.useExternalService);
    Instant now = Instant.now();
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setBackfills(
                Arrays.asList(new ScheduleBackfill(now.minusMillis(20500), now.minusMillis(10000))))
            .build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setState(ScheduleState.newBuilder().setPaused(true).build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ALL)
                    .build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 10);

    handle.backfill(
        Arrays.asList(
            new ScheduleBackfill(now.minusMillis(5500), now.minusMillis(2500)),
            new ScheduleBackfill(now.minusMillis(2500), now)));
    waitForActions(handle, 15);
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void describeSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .build();
    String scheduleId = UUID.randomUUID().toString();
    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId("test")
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(Collections.singletonMap("memokey1", "memoval1"))
            .build();

    Schedule schedule =
        Schedule.newBuilder(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(TestWorkflows.TestWorkflow1.class)
                    .setArgs("arg")
                    .setOptions(wfOptions)
                    .build(),
                ScheduleSpec.newBuilder()
                    .setCalendars(
                        Arrays.asList(
                            ScheduleCalendarSpec.newBuilder()
                                .setSeconds(Arrays.asList(new ScheduleRange(1, 0, 1)))
                                .setMinutes(Arrays.asList(new ScheduleRange(2, 3)))
                                .setHour(Arrays.asList(new ScheduleRange(4, 5, 6)))
                                .setDayOfMonth(Arrays.asList(new ScheduleRange(7)))
                                .setMonth(Arrays.asList(new ScheduleRange(9)))
                                .setYear(Arrays.asList(new ScheduleRange(2080)))
                                // Intentionally leave day of week absent to check default
                                .setComment("spec comment 1")
                                .build()))
                    .setCronExpressions(Arrays.asList("0 12 * * MON"))
                    .setSkip(
                        Arrays.asList(
                            ScheduleCalendarSpec.newBuilder()
                                .setYear(Arrays.asList(new ScheduleRange(2050)))
                                .build()))
                    .setIntervals(
                        Arrays.asList(
                            new ScheduleIntervalSpec(Duration.ofDays(10), Duration.ofDays(2))))
                    .setStartAt(Instant.parse("2060-12-03T10:15:30.00Z"))
                    .setJitter(Duration.ofSeconds(80))
                    .build())
            .setState(
                ScheduleState.newBuilder()
                    .setRemainingActions(30)
                    .setPaused(true)
                    .setNote("sched note 1")
                    .build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE)
                    .setPauseOnFailure(true)
                    .setCatchupWindow(Duration.ofMinutes(5))
                    .build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    //
    Assert.assertEquals(scheduleId, description.getId());
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    //
    Assert.assertEquals(
        ScheduleActionStartWorkflow.class, description.getSchedule().getAction().getClass());
    ScheduleActionStartWorkflow startWfAction =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    Assert.assertEquals("TestWorkflow1", startWfAction.getWorkflowType());
    EncodedValues parameters = startWfAction.getArgs();
    Assert.assertEquals("arg", parameters.get(0, String.class));
    EncodedValues encodedMemo =
        (EncodedValues) startWfAction.getOptions().getMemo().get("memokey1");
    String memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval1", memoValue);
    //
    Assert.assertEquals(
        ScheduleSpec.newBuilder(description.getSchedule().getSpec())
            .setCronExpressions(Collections.emptyList())
            .setCalendars(
                Arrays.asList(
                    description.getSchedule().getSpec().getCalendars().get(0),
                    ScheduleCalendarSpec.newBuilder()
                        .setSeconds(Arrays.asList(new ScheduleRange(0)))
                        .setMinutes(Arrays.asList(new ScheduleRange(0)))
                        .setHour(Arrays.asList(new ScheduleRange(12)))
                        .setDayOfMonth(Arrays.asList(new ScheduleRange(1, 31)))
                        .setMonth(Arrays.asList(new ScheduleRange(1, 12)))
                        .setDayOfWeek(Arrays.asList(new ScheduleRange(1)))
                        .build()))
            .build(),
        description.getSchedule().getSpec());
    Assert.assertEquals(schedule.getPolicy(), description.getSchedule().getPolicy());
    Assert.assertEquals(schedule.getState(), description.getSchedule().getState());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void updateSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create the schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);

    ScheduleDescription description = handle.describe();
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    ScheduleActionStartWorkflow startWfAction =
        ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    EncodedValues encodedMemo =
        (EncodedValues) startWfAction.getOptions().getMemo().get("memokey1");
    String memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval1", memoValue);

    handle.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder = Schedule.newBuilder(input.getDescription().getSchedule());
          ScheduleActionStartWorkflow wfAction =
              ((ScheduleActionStartWorkflow) input.getDescription().getSchedule().getAction());
          WorkflowOptions wfOptions =
              WorkflowOptions.newBuilder(wfAction.getOptions())
                  .setWorkflowTaskTimeout(Duration.ofMinutes(7))
                  .setMemo(Collections.singletonMap("memokey3", "memoval3"))
                  .build();
          builder.setAction(
              ScheduleActionStartWorkflow.newBuilder(wfAction).setOptions(wfOptions).build());
          return new ScheduleUpdate(builder.build());
        });
    description = handle.describe();
    Assert.assertEquals(
        ScheduleActionStartWorkflow.class, description.getSchedule().getAction().getClass());
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    startWfAction = ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    encodedMemo = (EncodedValues) startWfAction.getOptions().getMemo().get("memokey3");
    memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval3", memoValue);

    Assert.assertEquals(
        Duration.ofMinutes(7),
        ((ScheduleActionStartWorkflow) description.getSchedule().getAction())
            .getOptions()
            .getWorkflowTaskTimeout());
    // Update the schedule state
    Instant expectedUpdateTime = description.getInfo().getLastUpdatedAt();
    handle.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder =
              Schedule.newBuilder(
                  input.getDescription().getSchedule().getAction(),
                  ScheduleSpec.getDefaultInstance());
          builder.setState(ScheduleState.newBuilder().setPaused(true).build());
          return new ScheduleUpdate(builder.build());
        });
    description = handle.describe();
    //
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    startWfAction = ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    encodedMemo = (EncodedValues) startWfAction.getOptions().getMemo().get("memokey3");
    memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval3", memoValue);
    //
    Assert.assertNotEquals(expectedUpdateTime, description.getInfo().getLastUpdatedAt());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void listSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create the schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .build();
    Schedule schedule =
        createTestSchedule()
            .setState(ScheduleState.newBuilder().setPaused(true).setNote("schedule list").build())
            .build();
    // Append a unique prefix to the ID to avoid conflict with other schedules.
    String scheduleIdPrefix = UUID.randomUUID().toString();
    String scheduleId = scheduleIdPrefix + "/" + UUID.randomUUID();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    // Add delay for schedules to appear
    testWorkflowRule.sleep(Duration.ofSeconds(2));
    // List all schedules and filter
    Stream<ScheduleListDescription> scheduleStream =
        client.listSchedules(ScheduleListOptions.newBuilder().build());
    List<ScheduleListDescription> listedSchedules =
        scheduleStream
            .filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix))
            .collect(Collectors.toList());
    Assert.assertEquals(1, listedSchedules.size());
    // Verify the schedule description
    ScheduleListDescription listDescription = listedSchedules.get(0);
    Assert.assertEquals("memoval2", listDescription.getMemo("memokey2", String.class));
    Assert.assertEquals(scheduleId, listDescription.getScheduleId());
    // Verify the state
    Assert.assertEquals("schedule list", listDescription.getSchedule().getState().getNote());
    Assert.assertEquals(true, listDescription.getSchedule().getState().isPaused());
    // Verify the spec
    Assert.assertEquals(
        ScheduleSpec.newBuilder(schedule.getSpec())
            .setIntervals(
                Arrays.asList(
                    new ScheduleIntervalSpec(Duration.ofSeconds(1), Duration.ofSeconds(0))))
            .setCalendars(Collections.emptyList())
            .setCronExpressions(Collections.emptyList())
            .setSkip(Collections.emptyList())
            .setTimeZoneName("")
            .build(),
        listDescription.getSchedule().getSpec());
    // Verify the action
    Assert.assertEquals(
        ScheduleListActionStartWorkflow.class,
        listDescription.getSchedule().getAction().getClass());
    ScheduleListActionStartWorkflow action =
        (ScheduleListActionStartWorkflow) listDescription.getSchedule().getAction();
    Assert.assertEquals("TestWorkflow1", action.getWorkflow());
    // Create two additional schedules
    client.createSchedule(scheduleIdPrefix + UUID.randomUUID(), schedule, options);
    client.createSchedule(scheduleIdPrefix + UUID.randomUUID(), schedule, options);
    // Add delay for schedules to appear
    testWorkflowRule.sleep(Duration.ofSeconds(2));
    // List all schedules and filter
    scheduleStream = client.listSchedules(ScheduleListOptions.newBuilder().build());
    long listedSchedulesCount =
        scheduleStream.filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix)).count();
    Assert.assertEquals(3, listedSchedulesCount);
    // Cleanup all schedules
    scheduleStream = client.listSchedules(ScheduleListOptions.newBuilder().build());
    scheduleStream
        .filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix))
        .forEach(
            s -> {
              client.getHandle(s.getScheduleId()).delete();
            });
  }

  public static class QuickWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String arg) {
      return null;
    }
  }
}
