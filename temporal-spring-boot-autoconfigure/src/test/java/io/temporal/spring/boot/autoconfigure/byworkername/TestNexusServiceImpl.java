package io.temporal.spring.boot.autoconfigure.byworkername;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.spring.boot.NexusServiceImpl;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestNexusService;
import org.springframework.stereotype.Component;

@Component("TestNexusServiceImpl")
@NexusServiceImpl(workers = "mainWorker")
@ServiceImpl(service = TestNexusService.class)
public class TestNexusServiceImpl {
  @OperationImpl
  public OperationHandler<String, String> operation() {
    // Implemented inline
    return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
  }
}
