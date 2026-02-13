package io.temporal.spring.boot.autoconfigure.bytaskqueue;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component("TestActivityImpl")
@ActivityImpl(taskQueues = "${default-queue.name:UnitTest}")
public class TestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return input;
  }
}
