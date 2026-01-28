package io.temporal.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @deprecated typed search attributes should be used instead.
 */
@Deprecated
public class SearchAttribute {
  /**
   * Passing this value as a search attribute value into {@link
   * io.temporal.workflow.Workflow#upsertSearchAttributes(Map)} will lead to unsetting the search
   * attribute with the corresponded name if any present.
   */
  public static final List<Object> UNSET_VALUE = Collections.emptyList();
}
