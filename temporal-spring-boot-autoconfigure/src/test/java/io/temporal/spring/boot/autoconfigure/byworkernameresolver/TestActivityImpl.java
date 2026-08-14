package io.temporal.spring.boot.autoconfigure.byworkernameresolver;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component("ResolverTestActivityImpl")
@ActivityImpl(workers = "${worker.name}")
public class TestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return input;
  }
}
