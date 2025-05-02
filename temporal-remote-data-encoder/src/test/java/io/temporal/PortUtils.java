package io.temporal;

import java.io.IOException;
import java.net.ServerSocket;

class PortUtils {
  static int getFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
