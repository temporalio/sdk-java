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

package io.temporal.internal.testservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SelfAdvancingTimerImplTest {

  private static final int INITIAL_TIME = 1;
  private static final long INITIAL_SYSTEM_TIME = System.currentTimeMillis();

  private Clock mockedSystemClock;
  private SelfAdvancingTimerImpl fixedTimer;
  private LongSupplier timerBasedClock;

  @Before
  public void setUp() throws Exception {
    this.mockedSystemClock = mock(Clock.class);
    when(mockedSystemClock.millis()).thenReturn(INITIAL_SYSTEM_TIME);
    this.fixedTimer = new SelfAdvancingTimerImpl(INITIAL_TIME, mockedSystemClock);
    this.timerBasedClock = fixedTimer.getClock();
  }

  @After
  public void tearDown() throws Exception {
    fixedTimer.shutdown();
  }

  @Test
  public void testSchedule() throws InterruptedException {
    AtomicLong captured = new AtomicLong();
    fixedTimer.schedule(Duration.ofDays(1), () -> captured.set(timerBasedClock.getAsLong()));
    Thread.sleep(100);
    assertTrue(Duration.ofDays(1).toMillis() + INITIAL_TIME <= captured.get());
    assertTrue(Duration.ofDays(1).toMillis() + INITIAL_TIME <= timerBasedClock.getAsLong());
    long start = timerBasedClock.getAsLong();
    fixedTimer.schedule(Duration.ofSeconds(123), () -> captured.set(timerBasedClock.getAsLong()));
    assertTrue(timerBasedClock.getAsLong() - start >= Duration.ofSeconds(123).toMillis());
  }

  @Test
  public void testOrdering() throws InterruptedException {
    List<Long> captured = Collections.synchronizedList(new ArrayList<>());
    fixedTimer.lockTimeSkipping("unit test");
    fixedTimer.schedule(Duration.ofSeconds(100), () -> captured.add(timerBasedClock.getAsLong()));
    when(mockedSystemClock.millis()).thenReturn(INITIAL_SYSTEM_TIME + 5);
    fixedTimer.schedule(Duration.ofSeconds(20), () -> captured.add(timerBasedClock.getAsLong()));
    when(mockedSystemClock.millis()).thenReturn(INITIAL_SYSTEM_TIME + 10);
    fixedTimer.schedule(Duration.ofSeconds(10), () -> captured.add(timerBasedClock.getAsLong()));
    when(mockedSystemClock.millis()).thenReturn(INITIAL_SYSTEM_TIME + 15);
    fixedTimer.schedule(Duration.ofSeconds(1), () -> captured.add(timerBasedClock.getAsLong()));
    fixedTimer.unlockTimeSkipping("unit test");
    Thread.sleep(100);
    List<Long> expected =
        Arrays.asList(
            INITIAL_TIME + Duration.ofSeconds(1).toMillis() + 15,
            INITIAL_TIME + Duration.ofSeconds(10).toMillis() + 10,
            INITIAL_TIME + Duration.ofSeconds(20).toMillis() + 5,
            INITIAL_TIME + Duration.ofSeconds(100).toMillis());
    for (int i = 0; i < captured.size(); i++) {
      assertEquals(expected.get(i), captured.get(i));
    }
  }

  @Test
  public void testSkipTo() throws InterruptedException {
    List<Long> captured = Collections.synchronizedList(new ArrayList<>());
    fixedTimer.lockTimeSkipping("unit test");

    fixedTimer.schedule(Duration.ofSeconds(100), () -> captured.add(timerBasedClock.getAsLong()));
    fixedTimer.schedule(Duration.ofSeconds(200), () -> captured.add(timerBasedClock.getAsLong()));

    fixedTimer.skipTo(Instant.ofEpochMilli(INITIAL_TIME + Duration.ofSeconds(99).toMillis()));
    Thread.sleep(100);
    assertEquals(0, captured.size());

    fixedTimer.skipTo(Instant.ofEpochMilli(INITIAL_TIME + Duration.ofSeconds(100).toMillis()));
    Thread.sleep(100);
    assertEquals("Jump by 100s should trigger the first task", 1, captured.size());

    captured.clear();

    fixedTimer.skipTo(Instant.ofEpochMilli(INITIAL_TIME + Duration.ofSeconds(195).toMillis()));
    Thread.sleep(100);
    assertEquals(0, captured.size());

    when(mockedSystemClock.millis())
        .thenReturn(INITIAL_SYSTEM_TIME + Duration.ofSeconds(5).toMillis());
    fixedTimer.pump();
    Thread.sleep(100);
    assertEquals(
        "Jump by 195s and advancement of base clock by 5s should trigger the second task",
        1,
        captured.size());
  }

  @Test
  public void testSkip() throws InterruptedException {
    List<Long> captured = Collections.synchronizedList(new ArrayList<>());
    fixedTimer.lockTimeSkipping("unit test");

    fixedTimer.schedule(Duration.ofSeconds(100), () -> captured.add(timerBasedClock.getAsLong()));
    fixedTimer.schedule(Duration.ofSeconds(200), () -> captured.add(timerBasedClock.getAsLong()));

    fixedTimer.skip(Duration.ofSeconds(99));
    Thread.sleep(100);
    assertEquals(0, captured.size());

    fixedTimer.skip(Duration.ofSeconds(1));
    Thread.sleep(100);
    assertEquals("Jump by 100s should trigger the first task", 1, captured.size());

    captured.clear();

    fixedTimer.skip((Duration.ofSeconds(95)));
    Thread.sleep(100);
    assertEquals(0, captured.size());

    when(mockedSystemClock.millis())
        .thenReturn(INITIAL_SYSTEM_TIME + Duration.ofSeconds(5).toMillis());
    fixedTimer.pump();
    Thread.sleep(100);
    assertEquals(
        "Jump by 195s and advancement of base clock by 5s should trigger the second task",
        1,
        captured.size());
  }
}
