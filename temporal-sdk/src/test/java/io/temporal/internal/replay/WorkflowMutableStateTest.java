package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WorkflowMutableStateTest {

  @Test
  public void testUpsertSearchAttributes() {
    WorkflowExecutionStartedEventAttributes startAttr =
        WorkflowExecutionStartedEventAttributes.getDefaultInstance();
    WorkflowMutableState workflowMutableState = new WorkflowMutableState(startAttr);

    Map<String, Object> indexedFields = new HashMap<>();
    indexedFields.put("CustomKeywordField", "key");

    SearchAttributes searchAttributes = SearchAttributesUtil.encode(indexedFields);

    workflowMutableState.upsertSearchAttributes(searchAttributes);

    SearchAttributes decodedAttributes = workflowMutableState.getSearchAttributes();
    assertNotNull(decodedAttributes);

    assertEquals(
        "key", SearchAttributesUtil.decode(decodedAttributes).get("CustomKeywordField").get(0));
  }
}
