package io.temporal.internal.util;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import org.junit.Test;

public class MethodExtractorTest {

  @ActivityInterface
  public interface GreetActivity {
    @ActivityMethod(name = "Greet")
    String greet(String name);
  }

  @ActivityInterface
  public interface OtherActivity {
    @ActivityMethod(name = "Other")
    void other();
  }

  @Test
  public void testExtractReturnsCorrectActivityTypeName() {
    Method method = MethodExtractor.extract(GreetActivity.class, GreetActivity::greet);
    String typeName = MethodExtractor.activityTypeName(GreetActivity.class, method);
    assertEquals("Greet", typeName);
  }

  @Test(expected = NoSuchMethodError.class)
  @SuppressWarnings("unchecked")
  public void testExtractWithMethodFromWrongInterfaceThrowsNoSuchMethodError() {
    // OtherActivity::other is from a different interface; the proxy cast fails,
    // captured stays null, and ME throws NoSuchMethodError.
    Functions.Proc1<GreetActivity> wrongRef =
        (Functions.Proc1<GreetActivity>)
            (Object) (Functions.Proc1<OtherActivity>) OtherActivity::other;
    MethodExtractor.extract(GreetActivity.class, wrongRef);
  }
}
