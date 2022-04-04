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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.*;
import io.temporal.internal.testservice.*;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestServer {
  private static final Logger log = LoggerFactory.getLogger(TestServer.class);

  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: <command> <port> <flags>");
      System.err.println("Flags:");
      System.err.println("--enable-time-skipping - to enable time skipping on start");
      return;
    }
    int port = Integer.parseInt(args[0]);
    boolean enableTimeSkipping = false;

    // we can't continue doing this. If there is at least one more flag or parameter we should
    // incorporate a framework like picocli
    if (args.length > 1) {
      if ("--enable-time-skipping".equalsIgnoreCase(args[1])) {
        enableTimeSkipping = true;
      } else {
        System.err.println("Unknown flag " + args[1]);
        return;
      }
    }
    PortBoundTestServer server = createPortBoundServer(port, !enableTimeSkipping);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
  }

  /**
   * Creates an out-of-process rather than in-process server, and does not set up a client. Useful,
   * for example, if you want to use the test service from other SDKs.
   *
   * @param port the port to listen on
   */
  public static PortBoundTestServer createPortBoundServer(int port) {
    return createPortBoundServer(port, true);
  }

  /**
   * Creates an out-of-process rather than in-process server, and does not set up a client. Useful,
   * for example, if you want to use the test service from other SDKs.
   *
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server. To make test server behave like a real one in respect to time, this
   *     flag should be {@code true}.
   * @param port the port to listen on
   */
  public static PortBoundTestServer createPortBoundServer(int port, boolean lockTimeSkipping) {
    TestServicesStarter testServicesStarter = new TestServicesStarter(lockTimeSkipping, 0);
    try {
      ServerBuilder<?> serverBuilder =
          Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
      GRPCServerHelper.registerServicesAndHealthChecks(
          testServicesStarter.getServices(), serverBuilder);
      Server outOfProcessServer = serverBuilder.build().start();
      return new PortBoundTestServer(testServicesStarter, outOfProcessServer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return created in-memory service
   */
  public static InProcessTestServer createServer() {
    return createServer(true, 0);
  }

  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server. To make test server behave like a real one in respect to time, this
   *     flag should be {@code true}.
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
        new InProcessGRPCServer(testServicesStarter.getServices());
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
     * TODO should be removed after moving registerDelayedCallback into Test Service API.
     * WorkflowService instance shouldn't be called directly.
     */
    @Deprecated
    public TestWorkflowService getWorkflowService() {
      return testServicesStarter.getWorkflowService();
    }

    @VisibleForTesting
    TestServicesStarter getStarter() {
      return testServicesStarter;
    }

    public ManagedChannel getChannel() {
      return inProcessServer.getChannel();
    }

    @Override
    public void close() {
      if (inProcessServer != null) {
        log.info("Shutting down in-process gRPC server");
        inProcessServer.shutdown();
        inProcessServer.awaitTermination(5, TimeUnit.SECONDS);
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
    public void close() {
      try {
        if (outOfProcessServer != null) {
          log.info("Shutting down port-bind gRPC server");
          outOfProcessServer.shutdown();
          if (!outOfProcessServer.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Fail to shutdown the server in time (5s)");
          }
        }

        log.info("Shutting down gRPC Services");
        testServicesStarter.close();

        if (outOfProcessServer != null) {
          outOfProcessServer.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("shutdown interrupted", e);
      }
    }
  }
}
