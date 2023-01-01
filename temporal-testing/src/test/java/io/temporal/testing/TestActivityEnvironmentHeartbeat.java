package io.temporal.testing;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestActivityEnvironmentHeartbeat {

  TestActivityEnvironment testActivityEnvironment = TestActivityEnvironment.newInstance();

  @Test
  void testHeartbeat() {
    String detailString = "details";
    testActivityEnvironment.registerActivitiesImplementations(new HeartbeatingActivityImpl());
    testActivityEnvironment.setHeartbeatDetails(detailString);
    HearbeatingActivity heartbeatActivityStub = testActivityEnvironment.newActivityStub(HearbeatingActivity.class);

    String result = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertEquals(detailString, result);

    String secondResult = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertNull(secondResult);
  }

  @Test
  void testHeartbeatNullWithoutSetting() {
    testActivityEnvironment.registerActivitiesImplementations(new HeartbeatingActivityImpl());
    HearbeatingActivity heartbeatActivityStub = testActivityEnvironment.newActivityStub(HearbeatingActivity.class);

    String result = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertNull(result);
  }

  @ActivityInterface
  public interface HearbeatingActivity {

    @ActivityMethod
    String heartbeatMethod();

  }

  static class HeartbeatingActivityImpl implements HearbeatingActivity {

    @Override
    public String heartbeatMethod() {
      Optional<String> maybeDetails = Activity.getExecutionContext().getHeartbeatDetails(String.class);
      return maybeDetails.orElse(null);
    }
  }

}
