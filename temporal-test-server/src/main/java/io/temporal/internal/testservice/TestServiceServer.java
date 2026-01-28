package io.temporal.internal.testservice;

import io.temporal.testserver.TestServer;

/**
 * @deprecated use {@link TestServer#main(String[])} with {@code --enable-time-skipping} to get the
 *     behavior of this starter method
 */
@Deprecated
public class TestServiceServer {

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: <command> <port>");
    }
    Integer port = Integer.parseInt(args[0]);

    TestServer.createPortBoundServer(port, false);
  }
}
