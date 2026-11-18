package io.temporal.internal.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.workflow.EventGroup;
import io.temporal.workflow.EventGroupOptions;
import io.temporal.workflow.Functions;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class EventGroupImplTest {

  private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  private static EventGroup group(String id, String label) {
    return WorkflowInternal.newEventGroup(
        id, EventGroupOptions.newBuilder().setLabel(label).build());
  }

  @Test
  public void idIsUsedVerbatim() {
    List<EventGroupMarker> markers =
        EventGroupScope.EMPTY.toMarkers(Collections.singletonList(group("c-id", "ccc")), CONVERTER);

    assertEquals(1, markers.size());
    assertEquals("c-id", markers.get(0).getLabel().getId());
  }

  @Test
  public void emptyIdIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> WorkflowInternal.newEventGroup("", null));
    assertThrows(IllegalArgumentException.class, () -> WorkflowInternal.newEventGroup(null, null));
  }

  @Test
  public void labelConvertsToJsonString() {
    Payload payload =
        EventGroupScope.EMPTY
            .toMarkers(Collections.singletonList(group("bbb", "Label B")), CONVERTER)
            .get(0)
            .getLabel()
            .getLabel();

    assertEquals("json/plain", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertEquals("Label B", CONVERTER.fromPayload(payload, String.class, String.class));
  }

  @Test
  public void emptyLabelIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> EventGroupOptions.newBuilder().setLabel(""));
  }

  @Test
  public void omittedLabelProducesNoPayload() {
    EventGroupMarker marker =
        EventGroupScope.EMPTY
            .toMarkers(
                Collections.singletonList(WorkflowInternal.newEventGroup("aaa", null)), CONVERTER)
            .get(0);

    assertFalse(marker.getLabel().hasLabel());
  }

  @Test
  public void labelIsCodecEncodedAndIdIsNot() {
    DataConverter codecConverter =
        new CodecDataConverter(
            CONVERTER, Collections.singletonList(new CodecDataConverterTest.PrefixPayloadCodec()));

    EventGroupMarker marker =
        EventGroupScope.EMPTY
            .toMarkers(Collections.singletonList(group("bbb", "Label B")), codecConverter)
            .get(0);

    assertEquals("bbb", marker.getLabel().getId());
    Payload label = marker.getLabel().getLabel();
    assertTrue(label.getData().startsWith(CodecDataConverterTest.PrefixPayloadCodec.PREFIX));
    assertEquals("Label B", codecConverter.fromPayload(label, String.class, String.class));
  }

  @Test
  public void groupsWithTheSameIdAreRecordedOnceWithTheFirstLabel() {
    List<EventGroupMarker> markers =
        EventGroupScope.EMPTY.toMarkers(
            Arrays.asList(group("b-id", "bbb1"), group("aaa", null), group("b-id", "bbb2")),
            CONVERTER);

    Map<String, EventGroupMarker.Label> byId = new HashMap<>();
    for (EventGroupMarker marker : markers) {
      byId.put(marker.getLabel().getId(), marker.getLabel());
    }
    assertEquals(2, markers.size());
    assertEquals(byId.keySet(), new HashSet<>(Arrays.asList("aaa", "b-id")));
    assertEquals(
        "bbb1", CONVERTER.fromPayload(byId.get("b-id").getLabel(), String.class, String.class));
  }

  @Test
  public void groupsNotCreatedByTheSdkAreRejected() {
    EventGroup foreign =
        new EventGroup() {
          @Override
          public String getId() {
            return "foreign";
          }

          @Override
          public String getLabel() {
            return null;
          }

          @Override
          public void run(Functions.Proc proc) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <R> R run(Functions.Func<R> func) {
            throw new UnsupportedOperationException();
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () -> EventGroupScope.EMPTY.toMarkers(Collections.singletonList(foreign), CONVERTER));
  }
}
