package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.junit.Test;

public class ListUtilsTest {

  @Test
  public void flattensNestedCollectionsInOrder() {
    List<Integer> flat =
        ListUtils.flatten(Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3), Arrays.asList(4, 5)));

    assertEquals(Arrays.asList(1, 2, 3, 4, 5), flat);
  }

  @Test
  public void preservesDuplicates() {
    List<Integer> flat = ListUtils.flatten(Arrays.asList(Arrays.asList(1, 1), Arrays.asList(1)));

    assertEquals(Arrays.asList(1, 1, 1), flat);
  }

  @Test
  public void skipsEmptyInnerCollections() {
    List<Integer> flat =
        ListUtils.flatten(
            Arrays.asList(
                Collections.<Integer>emptyList(),
                Arrays.asList(1),
                Collections.<Integer>emptyList()));

    assertEquals(Arrays.asList(1), flat);
  }

  @Test
  public void emptyOuterCollectionYieldsEmptyList() {
    assertTrue(ListUtils.flatten(Collections.<List<Integer>>emptyList()).isEmpty());
  }

  @Test
  public void acceptsMixedListImplementations() {
    List<List<Integer>> nested = new ArrayList<>();
    nested.add(new LinkedList<>(Arrays.asList(1, 2)));
    nested.add(new ArrayList<>(Arrays.asList(3)));

    assertEquals(Arrays.asList(1, 2, 3), ListUtils.flatten(nested));
  }

  @Test
  public void returnsIndependentCopy() {
    List<Integer> inner = new ArrayList<>(Arrays.asList(1, 2));
    List<List<Integer>> outer = new ArrayList<>();
    outer.add(inner);

    List<Integer> flat = ListUtils.flatten(outer);
    assertNotSame(inner, flat);

    // Mutating an input after flattening must not change the result.
    inner.add(3);
    assertEquals(Arrays.asList(1, 2), flat);

    // Mutating the result must not change the inputs.
    flat.add(99);
    assertEquals(Arrays.asList(1, 2, 3), inner);
  }
}
