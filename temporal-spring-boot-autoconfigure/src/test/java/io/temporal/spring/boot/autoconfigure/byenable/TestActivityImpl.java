package io.temporal.spring.boot.autoconfigure.byenable;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("auto-discovery-enable")
@ActivityImpl(workers = "mainWorker")
public class TestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return input;
  }
}
