package io.temporal.spring.boot.autoconfigure.byworkernameresolver;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("ResolverTestActivityImpl")
@ActivityImpl(workers = "${worker.name}")
@Profile("auto-discovery-by-worker-name-resolver")
public class TestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return input;
  }
}
