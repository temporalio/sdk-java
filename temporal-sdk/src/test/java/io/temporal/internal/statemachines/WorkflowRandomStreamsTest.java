package io.temporal.internal.statemachines;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import io.temporal.workflow.WorkflowRandomStream;
import java.util.Base64;
import org.junit.Test;

public class WorkflowRandomStreamsTest {
  private static final String RUN_ID = "runID";
  private static final String NAME = "io.temporal.test";

  @Test
  public void goldenSeedAndBytes() {
    assertEquals(
        "cYwA+k67hflWG1GVOzY897A19H2s16mOOzlic16UFtI=",
        Base64.getEncoder().encodeToString(WorkflowRandomStreams.deriveSeed(RUN_ID, NAME)));

    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    randoms.updateRunId(RUN_ID);
    byte[] bytes = new byte[32];
    randoms.get(NAME).nextBytes(bytes);

    assertEquals(
        "wY4Mb9uhREeU08hmLgZRqar87inHCMHTkHCss0U8Wi0=", Base64.getEncoder().encodeToString(bytes));
    assertEquals(-4499645303130864569L, randoms(RUN_ID).get(NAME).nextLong());
  }

  @Test
  public void seedFramingSeparatesRunIdAndName() {
    assertNotEquals(
        Base64.getEncoder().encodeToString(WorkflowRandomStreams.deriveSeed("ab", "c")),
        Base64.getEncoder().encodeToString(WorkflowRandomStreams.deriveSeed("a", "bc")));
  }

  @Test
  public void sameNameContinuesAndNamesAreIndependent() {
    WorkflowRandomStreams interleaved = randoms(RUN_ID);
    WorkflowRandomStream first = interleaved.get(NAME);
    long firstValue = first.nextLong();
    long otherValue = interleaved.get("other").nextLong();
    WorkflowRandomStream second = interleaved.get(NAME);
    long secondValue = second.nextLong();

    WorkflowRandomStreams isolated = randoms(RUN_ID);
    assertSame(first, second);
    assertEquals(firstValue, isolated.get(NAME).nextLong());
    assertEquals(secondValue, isolated.get(NAME).nextLong());
    assertEquals(otherValue, isolated.get("other").nextLong());
    assertNotEquals(firstValue, otherValue);
  }

  @Test
  public void runIdUpdateReseedsExistingStreamInPlace() {
    WorkflowRandomStreams randoms = randoms(RUN_ID);
    WorkflowRandomStream before = randoms.get(NAME);
    before.nextLong();

    randoms.updateRunId("new-run");
    WorkflowRandomStream after = randoms.get(NAME);

    WorkflowRandomStreams fresh = randoms("new-run");
    assertSame(before, after);
    assertEquals(fresh.get(NAME).nextLong(), after.nextLong());
  }

  @Test
  public void streamCreatedAfterRunIdUpdateUsesNewRun() {
    WorkflowRandomStreams randoms = randoms(RUN_ID);
    randoms.get("before-reset").nextLong();
    randoms.updateRunId("new-run");

    byte[] actual = new byte[32];
    randoms.get(NAME).nextBytes(actual);

    byte[] expected = new byte[32];
    randoms("new-run").get(NAME).nextBytes(expected);
    assertArrayEquals(expected, actual);
  }

  private static WorkflowRandomStreams randoms(String runId) {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    randoms.updateRunId(runId);
    return randoms;
  }
}
