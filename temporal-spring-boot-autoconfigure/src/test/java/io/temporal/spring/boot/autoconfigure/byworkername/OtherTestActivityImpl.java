package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("TestActivityImpl")
@ActivityImpl(workers = "mainWorker")
@Profile("auto-discovery-with-profile")
public class OtherTestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return "other workflow " + input;
  }
}
