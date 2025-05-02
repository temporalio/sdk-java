package io.temporal.common.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class POJOActivityImplMetadataTest {

  static class DImpl
      implements POJOActivityInterfaceMetadataTest.D, POJOActivityInterfaceMetadataTest.E {

    @Override
    public void a() {}

    @Override
    public void b() {}

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  static class DEImpl implements POJOActivityInterfaceMetadataTest.DE {

    @Override
    public void a() {}

    @Override
    public void b() {}

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  static class DuplicatedNameImpl
      implements POJOActivityInterfaceMetadataTest.F, POJOActivityInterfaceMetadataTest.C {

    @Override
    public void a() {}

    @Override
    public void b() {}

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void f() {}
  }

  static class GImpl implements POJOActivityInterfaceMetadataTest.G {
    @Override
    public void g() {}
  }

  class EmptyImpl implements POJOActivityInterfaceMetadataTest.Empty {
    public void foo() {}
  }

  class NoActivityImpl implements POJOActivityInterfaceMetadataTest.A {

    @Override
    public void a() {}
  }

  @Test(expected = IllegalArgumentException.class)
  public void testActivityRegistration() {
    POJOActivityImplMetadata.newInstance(EmptyImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoActivityInterfaceRegistration() {
    POJOActivityImplMetadata.newInstance(NoActivityImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testDuplicatedActivityTypeRegistration() {
    POJOActivityImplMetadata.newInstance(DuplicatedNameImpl.class);
  }

  @Test
  public void testActivityImplementationRegistration() {
    Set<String> expected = new HashSet<>();
    expected.add("AM_C_bb");
    expected.add("AM_E_bb");
    expected.add("C_A");
    expected.add("C_B");
    expected.add("C_C");
    expected.add("D");
    expected.add("A");
    expected.add("B");

    POJOActivityImplMetadata activityImplMetadata =
        POJOActivityImplMetadata.newInstance(DImpl.class);
    List<POJOActivityMethodMetadata> activityMethods = new ArrayList<>();
    for (POJOActivityInterfaceMetadata activityInterface :
        activityImplMetadata.getActivityInterfaces()) {
      activityMethods.addAll(activityInterface.getMethodsMetadata());
    }
    Set<String> activityTypes = new HashSet<>();
    for (POJOActivityMethodMetadata activityMethod : activityMethods) {
      activityTypes.add(activityMethod.getActivityTypeName());
    }
    assertEquals(expected, activityTypes);
  }

  @Test
  public void testDuplicatedActivityImplementationRegistration() {
    try {
      POJOActivityImplMetadata.newInstance(DEImpl.class);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
    }
  }

  @Test
  public void testNonPublicInterface() {
    try {
      POJOActivityImplMetadata.newInstance(GImpl.class);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains("Interface with @ActivityInterface annotation must be public"));
    }
  }
}
