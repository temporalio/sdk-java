package io.temporal.springai.util;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.springai.tool.SideEffectTool;
import io.temporal.springai.tool.SideEffectToolCallback;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

class TemporalToolUtilTest {

  // --- Test fixture classes ---

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

  static class UnannotatedTools {
    @Tool(description = "Some tool")
    public String doSomething() {
      return "result";
    }
  }

  // --- Tests for plain tools (execute in workflow context) ---

  @Test
  void convertTools_plainTool_producesStandardCallbacks() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new MathTools());

    assertEquals(2, callbacks.size());
    for (ToolCallback cb : callbacks) {
      assertFalse(
          cb instanceof SideEffectToolCallback,
          "Plain tool should not produce SideEffectToolCallback");
    }
  }

  @Test
  void convertTools_plainTool_hasCorrectToolNames() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new MathTools());

    List<String> toolNames =
        callbacks.stream().map(cb -> cb.getToolDefinition().name()).sorted().toList();
    assertEquals(List.of("add", "multiply"), toolNames);
  }

  @Test
  void convertTools_unannotatedTool_producesStandardCallbacks() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools(new UnannotatedTools());

    assertEquals(1, callbacks.size());
    assertEquals("doSomething", callbacks.get(0).getToolDefinition().name());
  }

  @Test
  void convertTools_plainString_throwsIllegalState() {
    // String has no @Tool methods — Spring AI's ToolCallbacks.from() throws
    assertThrows(IllegalStateException.class, () -> TemporalToolUtil.convertTools("not a tool"));
  }

  // --- Tests for @SideEffectTool ---

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

  // --- Tests for null handling ---

  @Test
  void convertTools_nullObject_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> TemporalToolUtil.convertTools((Object) null));
  }

  @Test
  void convertTools_nullInArray_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> TemporalToolUtil.convertTools(new MathTools(), null));
  }

  // --- Tests for empty input ---

  @Test
  void convertTools_emptyArray_returnsEmptyList() {
    List<ToolCallback> callbacks = TemporalToolUtil.convertTools();
    assertTrue(callbacks.isEmpty());
  }

  // --- Tests for mixed tool types ---

  @Test
  void convertTools_mixedPlainAndSideEffect_allConvertCorrectly() {
    List<ToolCallback> callbacks =
        TemporalToolUtil.convertTools(new MathTools(), new TimestampTools(), new RandomTools());

    assertEquals(4, callbacks.size());

    long sideEffectCount =
        callbacks.stream().filter(cb -> cb instanceof SideEffectToolCallback).count();
    long standardCount =
        callbacks.stream().filter(cb -> !(cb instanceof SideEffectToolCallback)).count();

    assertEquals(2, sideEffectCount);
    assertEquals(2, standardCount);
  }

  @Test
  void convertTools_mixedWithUnannotated_allSucceed() {
    List<ToolCallback> callbacks =
        TemporalToolUtil.convertTools(new MathTools(), new UnannotatedTools());

    assertEquals(3, callbacks.size()); // 2 from MathTools + 1 from UnannotatedTools
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
