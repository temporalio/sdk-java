package io.temporal.internal.statemachines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.google.common.io.BaseEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class WorkflowRandomStreamsTest {
  private static final String RUN_ID = "runID";
  private static final String NAME = "io.temporal.test";

  @Test
  public void deriveSeed() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    long seed = randoms.deriveSeed(RUN_ID, NAME);
    assertNotEquals(seed, randoms.deriveSeed("other", NAME));
    assertNotEquals(seed, randoms.deriveSeed(RUN_ID, "other"));
    assertNotEquals(seed, randoms.deriveSeed("other", "other"));
  }

  /** Pins the seed derivation and resulting byte stream. Changing either breaks replay. */
  @Test
  public void getRandomStreamGolden() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    assertEquals(8181915698088084985L, randoms.deriveSeed(RUN_ID, NAME));

    byte[] bytes = new byte[32];
    randoms.get(RUN_ID, NAME).nextBytes(bytes);
    assertEquals(
        "1c1d4dd36999ff851d72aa41f660ecd3220d83499109ba3ba24e455a1b776c21",
        BaseEncoding.base16().lowerCase().encode(bytes));
  }

  @Test
  public void deriveSeedSeparators() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    assertNotEquals(randoms.deriveSeed("ab", "c"), randoms.deriveSeed("a", "bc"));
  }

  /** A second lookup under the same name continues the sequence rather than restarting it. */
  @Test
  public void getRandomStreamMemoizes() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();

    Random first = randoms.get(RUN_ID, NAME);
    long firstDraw = first.nextLong();

    Random second = randoms.get(RUN_ID, NAME);
    long secondDraw = second.nextLong();

    assertSame(first, second);
    assertNotEquals(firstDraw, secondDraw);
  }

  /**
   * Interleaving draws across two names yields the same sequence per name as drawing from each on
   * its own, so how often a workflow draws from one name cannot shift another.
   */
  @Test
  public void getRandomStreamNamesAreIndependent() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();
    Random first = randoms.get(RUN_ID, NAME);
    Random second = randoms.get(RUN_ID, "other");

    List<Long> interleavedA = new ArrayList<>();
    List<Long> interleavedB = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      interleavedA.add(first.nextLong());
      interleavedB.add(second.nextLong());
    }

    assertEquals(solo(NAME, 3), interleavedA);
    assertEquals(solo("other", 3), interleavedB);
    assertNotEquals(interleavedA, interleavedB);
  }

  @Test
  public void reseedRandomsInPlace() {
    WorkflowRandomStreams randoms = new WorkflowRandomStreams();

    Random first = randoms.get(RUN_ID, NAME);
    randoms.reseed("other");
    Random second = randoms.get("other", NAME);

    assertSame(first, second);
    assertEquals(new WorkflowRandomStreams().get("other", NAME).nextLong(), second.nextLong());
  }

  private static List<Long> solo(String name, int draws) {
    Random random = new WorkflowRandomStreams().get(RUN_ID, name);
    List<Long> result = new ArrayList<>();
    for (int i = 0; i < draws; i++) {
      result.add(random.nextLong());
    }
    return result;
  }
}
