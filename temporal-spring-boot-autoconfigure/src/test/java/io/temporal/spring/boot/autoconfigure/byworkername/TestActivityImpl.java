package io.temporal.spring.boot.autoconfigure.byworkername;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component("TestActivityImpl")
@ActivityImpl(workers = "mainWorker")
public class TestActivityImpl implements TestActivity {
  @Override
  public String execute(String input) {
    return input;
  }
}
