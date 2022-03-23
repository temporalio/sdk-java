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

package io.temporal.testserver.functional;

import static org.junit.Assert.*;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import io.temporal.api.testservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.TestServiceStubs;
import io.temporal.serviceclient.TestServiceStubsOptions;
import io.temporal.testserver.TestServer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TimeSkippingTest {

  private TestServer.InProcessTestServer testServer;
  private TestServiceStubs testServiceStubs;

  @Before
  public void setUp() {
    this.testServer = TestServer.createServer(true);

    this.testServiceStubs =
        TestServiceStubs.newInstance(
            TestServiceStubsOptions.newBuilder()
                .setChannel(testServer.getChannel())
                .validateAndBuildWithDefaults());
  }

  @After
  public void tearDown() {
    this.testServiceStubs.shutdownNow();
    this.testServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    this.testServer.close();
  }

  @Test
  public void skipUnblocksSleepUntil() throws Exception {
    com.google.protobuf.Duration DURATION = ProtobufTimeUtils.toProtoDuration(Duration.ofDays(1));

    GetCurrentTimeResponse currentTime =
        testServiceStubs.blockingStub().getCurrentTime(Empty.newBuilder().build());
    ListenableFuture<SleepResponse> sleepFuture =
        testServiceStubs
            .futureStub()
            .sleepUntil(
                SleepUntilRequest.newBuilder()
                    .setTimestamp(
                        ProtobufTimeUtils.toProtoTimestamp(
                            ProtobufTimeUtils.toJavaInstant(currentTime.getTime())
                                .plus(Duration.ofDays(1))))
                    .build());
    Thread.sleep(500);
    assertFalse(sleepFuture.isDone());

    testServiceStubs.blockingStub().skip(SkipRequest.newBuilder().setDuration(DURATION).build());

    sleepFuture.get(1, TimeUnit.SECONDS);
  }
}
