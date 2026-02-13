package io.temporal.spring.boot.autoconfigure.bytaskqueue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.spring.boot.NexusServiceImpl;
import org.springframework.stereotype.Component;

@Component("TestNexusServiceImpl")
@NexusServiceImpl(taskQueues = "${default-queue.name:UnitTest}")
@ServiceImpl(service = TestNexusService.class)
public class TestNexusServiceImpl {
  @OperationImpl
  public OperationHandler<String, String> operation() {
    // Implemented inline
    return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
  }
}
