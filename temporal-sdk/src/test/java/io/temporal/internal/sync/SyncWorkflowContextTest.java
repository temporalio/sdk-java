package io.temporal.internal.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.replay.ReplayWorkflowContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SyncWorkflowContextTest {
  SyncWorkflowContext context;
  ReplayWorkflowContext mockReplayWorkflowContext = mock(ReplayWorkflowContext.class);

  @Before
  public void setUp() {
    this.context = DummySyncWorkflowContext.newDummySyncWorkflowContext();
    this.context.setReplayContext(mockReplayWorkflowContext);
  }

  @Test
  public void testUpsertSearchAttributes() {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = SearchAttributesUtil.encode(attr);

    context.upsertSearchAttributes(attr);
    verify(mockReplayWorkflowContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }
}
