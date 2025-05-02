package io.temporal.spring.boot.autoconfigure.byworkername;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;

@Service
public interface TestNexusService {
  @Operation
  String operation(String input);
}
