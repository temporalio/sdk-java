package io.temporal.springai.util;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.springai.tool.DeterministicTool;
import io.temporal.springai.tool.SideEffectTool;
import io.temporal.springai.tool.SideEffectToolCallback;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

class TemporalToolUtilTest {

  // --- Test fixture classes ---

  @DeterministicTool
  static class MathTools {
    @Tool(description = "Add two numbers")
    public int add(int a, int b) {
      return a + b;
    }

    @Tool(description = "Multiply two numbers")
    public int multiply(int a, int b) {
      return a * b;
    }
  }

  @SideEffectTool
  static class TimestampTools {
    @Tool(description = "Get the current timestamp")
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }

  @SideEffectTool
  static class RandomTools {
    @Tool(description = "Generate a random number")
    public double random() {
      return Math.random();
    }
  }

  // No annotation
  static class UnannotatedTools {
    @Tool(description = "Some tool")
    public String doSomething() {
      return "result";
    }
  }

  // --- Tests for convertTools with @DeterministicTool ---

  @Test
  void convertTools_deterministicTool_producesStandardCallbacks() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new MathTools());

    assertEquals(2, callbacks.size());
    // DeterministicTool callbacks should NOT be wrapped in SideEffectToolCallback
    for (ToolCallback cb : callbacks) {
      assertFalse(
          cb instanceof SideEffectToolCallback,
          "DeterministicTool should not produce SideEffectToolCallback");
    }
  }

  @Test
  void convertTools_deterministicTool_hasCorrectToolNames() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new MathTools());

    List<String> toolNames =
        callbacks.stream().map(cb -> cb.getToolDefinition().name()).sorted().toList();
    assertEquals(List.of("add", "multiply"), toolNames);
  }

  // --- Tests for convertTools with @SideEffectTool ---

  @Test
  void convertTools_sideEffectTool_producesSideEffectCallbackWrappers() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new TimestampTools());

    assertEquals(1, callbacks.size());
    assertInstanceOf(SideEffectToolCallback.class, callbacks.get(0));
  }

  @Test
  void convertTools_sideEffectTool_hasCorrectToolName() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new TimestampTools());

    assertEquals("currentTimeMillis", callbacks.get(0).getToolDefinition().name());
  }

  @Test
  void convertTools_sideEffectTool_delegateIsPreserved() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new TimestampTools());

    SideEffectToolCallback wrapper = (SideEffectToolCallback) callbacks.get(0);
    assertNotNull(wrapper.getDelegate());
    assertEquals("currentTimeMillis", wrapper.getDelegate().getToolDefinition().name());
  }

  // --- Tests for unknown/unannotated objects ---

  @Test
  void convertTools_unannotatedObject_throwsIllegalArgumentException() {
    UnannotatedTools unannotated = new UnannotatedTools();

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> TemporalToolUtil.convertTools(unannotated));
    assertTrue(ex.getMessage().contains("not a recognized Temporal primitive"));
    assertTrue(ex.getMessage().contains("@DeterministicTool"));
    assertTrue(ex.getMessage().contains("@SideEffectTool"));
    assertTrue(ex.getMessage().contains(UnannotatedTools.class.getName()));
  }

  @Test
  void convertTools_plainString_throwsIllegalArgumentException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> TemporalToolUtil.convertTools("not a tool"));
    assertTrue(ex.getMessage().contains("java.lang.String"));
  }

  // --- Tests for null handling ---

  @Test
  void convertTools_nullObject_throwsIllegalArgumentException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> TemporalToolUtil.convertTools((Object) null));
    assertTrue(ex.getMessage().contains("null"));
  }

  @Test
  void convertTools_nullInArray_throwsIllegalArgumentException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> TemporalToolUtil.convertTools(new MathTools(), null));
    assertTrue(ex.getMessage().contains("null"));
  }

  // --- Tests for empty input ---

  @Test
  void convertTools_emptyArray_returnsEmptyList() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools();
    assertTrue(callbacks.isEmpty());
  }

  // --- Tests for mixed tool types ---

  @Test
  void convertTools_mixedDeterministicAndSideEffect_allConvertCorrectly() {
    List<ToolCallback> callbacks =
        TemporalToolUtil.convertTools(new MathTools(), new TimestampTools(), new RandomTools());

    // MathTools has 2 methods, TimestampTools has 1, RandomTools has 1
    assertEquals(4, callbacks.size());

    long sideEffectCount =
        callbacks.stream().filter(cb -> cb instanceof SideEffectToolCallback).count();
    long standardCount =
        callbacks.stream().filter(cb -> !(cb instanceof SideEffectToolCallback)).count();

    // 2 from TimestampTools + RandomTools are SideEffectToolCallback
    assertEquals(2, sideEffectCount);
    // 2 from MathTools are standard
    assertEquals(2, standardCount);
  }

  @Test
  void convertTools_mixedWithUnannotated_throwsOnFirstUnannotated() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TemporalToolUtil.convertTools(new MathTools(), new UnannotatedTools()));
  }

  // --- Tests for isRecognizedToolType ---

  @Test
  void isRecognizedToolType_deterministicTool_returnsTrue() {
    assertTrue(TemporalToolUtil.isRecognizedToolType(new MathTools()));
  }

  @Test
  void isRecognizedToolType_sideEffectTool_returnsTrue() {
    assertTrue(TemporalToolUtil.isRecognizedToolType(new TimestampTools()));
  }

  @Test
  void isRecognizedToolType_unannotatedObject_returnsFalse() {
    assertFalse(TemporalToolUtil.isRecognizedToolType(new UnannotatedTools()));
  }

  @Test
  void isRecognizedToolType_plainObject_returnsFalse() {
    assertFalse(TemporalToolUtil.isRecognizedToolType("a string"));
    assertFalse(TemporalToolUtil.isRecognizedToolType(42));
  }

  @Test
  void isRecognizedToolType_null_returnsFalse() {
    assertFalse(TemporalToolUtil.isRecognizedToolType(null));
  }

  // --- Tests for TemporalStubUtil negative cases ---

  @Test
  void stubUtil_isActivityStub_nonProxy_returnsFalse() {
    assertFalse(TemporalStubUtil.isActivityStub(new MathTools()));
    assertFalse(TemporalStubUtil.isActivityStub("not a stub"));
    assertFalse(TemporalStubUtil.isActivityStub(null));
  }

  @Test
  void stubUtil_isLocalActivityStub_nonProxy_returnsFalse() {
    assertFalse(TemporalStubUtil.isLocalActivityStub(new MathTools()));
    assertFalse(TemporalStubUtil.isLocalActivityStub("not a stub"));
    assertFalse(TemporalStubUtil.isLocalActivityStub(null));
  }

  @Test
  void stubUtil_isChildWorkflowStub_nonProxy_returnsFalse() {
    assertFalse(TemporalStubUtil.isChildWorkflowStub(new MathTools()));
    assertFalse(TemporalStubUtil.isChildWorkflowStub("not a stub"));
    assertFalse(TemporalStubUtil.isChildWorkflowStub(null));
  }

  @Test
  void stubUtil_isNexusServiceStub_nonProxy_returnsFalse() {
    assertFalse(TemporalStubUtil.isNexusServiceStub(new MathTools()));
    assertFalse(TemporalStubUtil.isNexusServiceStub("not a stub"));
    assertFalse(TemporalStubUtil.isNexusServiceStub(null));
  }

  @Test
  void stubUtil_nonTemporalProxy_returnsFalse() {
    // A JDK dynamic proxy that is NOT a Temporal stub should return false for all checks
    Object proxy =
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Runnable.class},
            (p, method, args) -> null);

    assertFalse(TemporalStubUtil.isActivityStub(proxy));
    assertFalse(TemporalStubUtil.isLocalActivityStub(proxy));
    assertFalse(TemporalStubUtil.isChildWorkflowStub(proxy));
    assertFalse(TemporalStubUtil.isNexusServiceStub(proxy));
  }
}
