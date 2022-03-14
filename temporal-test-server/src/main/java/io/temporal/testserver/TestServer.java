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

package io.temporal.testserver;

import io.grpc.*;
import io.temporal.internal.testservice.*;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestServer {
  private static final Logger log = LoggerFactory.getLogger(TestServer.class);

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("Usage: <command> <port>");
    }
    Integer port = Integer.parseInt(args[0]);

    createPortBoundServer(port);
  }

  /**
   * Creates an out-of-process rather than in-process server, and does not set up a client. Useful,
   * for example, if you want to use the test service from other SDKs.
   *
   * @param port the port to listen on
   */
  public static PortBoundTestServer createPortBoundServer(int port) {
    TestServicesStarter testServicesStarter = new TestServicesStarter(false, 0);
    try {
      ServerBuilder<?> serverBuilder =
          Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
      GRPCServerHelper.registerServicesAndHealthChecks(
          Arrays.asList(
              testServicesStarter.getWorkflowService(), testServicesStarter.getOperatorService()),
          serverBuilder);
      Server outOfProcessServer = serverBuilder.build().start();
      return new PortBoundTestServer(testServicesStarter, outOfProcessServer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** @return created in-memory service */
  public static InProcessTestServer createServer() {
    return createServer(false, 0);
  }

  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server
   * @return created in-memory service
   */
  public static InProcessTestServer createServer(boolean lockTimeSkipping) {
    return createServer(lockTimeSkipping, 0);
  }

  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server
   * @param initialTimeMillis initial timestamp for the test server, {@link
   *     System#currentTimeMillis()} will be used if 0.
   * @return created in-memory service
   */
  public static InProcessTestServer createServer(boolean lockTimeSkipping, long initialTimeMillis) {
    TestServicesStarter testServicesStarter =
        new TestServicesStarter(lockTimeSkipping, initialTimeMillis);
    InProcessGRPCServer inProcessServer =
        new InProcessGRPCServer(
            Arrays.asList(
                testServicesStarter.getWorkflowService(),
                testServicesStarter.getOperatorService()));
    return new InProcessTestServer(testServicesStarter, inProcessServer);
  }

  public static final class InProcessTestServer implements Closeable {
    private final TestServicesStarter testServicesStarter;
    private final InProcessGRPCServer inProcessServer;

    private InProcessTestServer(
        TestServicesStarter testServicesStarter, InProcessGRPCServer inProcessServer) {
      this.testServicesStarter = testServicesStarter;
      this.inProcessServer = inProcessServer;
    }

    /**
     * TODO should be removed after exposing time skipping service. WorkflowService instance
     * shouldn't be called directly.
     */
    public TestWorkflowService getWorkflowService() {
      return testServicesStarter.getWorkflowService();
    }

    public ManagedChannel getChannel() {
      return inProcessServer.getChannel();
    }

    @Override
    public void close() {
      if (inProcessServer != null) {
        log.info("Shutting down in-process gRPC server");
        inProcessServer.shutdown();
        inProcessServer.awaitTermination(1, TimeUnit.SECONDS);
      }

      log.info("Shutting down gRPC Services");
      testServicesStarter.close();
    }
  }

  public static final class PortBoundTestServer implements Closeable {
    private final TestServicesStarter testServicesStarter;
    private final Server outOfProcessServer;

    private PortBoundTestServer(
        TestServicesStarter testServicesStarter, Server outOfProcessServer) {
      this.testServicesStarter = testServicesStarter;
      this.outOfProcessServer = outOfProcessServer;
    }

    @Override
    public void close() throws IOException {
      if (outOfProcessServer != null) {
        log.info("Shutting down port-bind gRPC server");
        outOfProcessServer.shutdown();
      }

      log.info("Shutting down gRPC Services");
      testServicesStarter.close();

      try {
        if (outOfProcessServer != null) {
          outOfProcessServer.awaitTermination(1, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("shutdown interrupted", e);
      }
    }
  }
}
