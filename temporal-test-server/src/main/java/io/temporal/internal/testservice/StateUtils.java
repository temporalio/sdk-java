package io.temporal.internal.testservice;

import static io.temporal.common.converter.EncodingKeys.METADATA_ENCODING_KEY;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

class StateUtils {
  private static Payload nullPayload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(null).get();
  private static Payload emptyListPayload =
      DefaultDataConverter.STANDARD_INSTANCE.toPayload(new String[] {}).get();

  /**
   * @return true if the workflow was completed not by workflow task completion result
   */
  public static boolean isWorkflowExecutionForcefullyCompleted(StateMachines.State state) {
    switch (state) {
      case TERMINATED:
      case TIMED_OUT:
        return true;
      default:
        return false;
    }
  }

  private static boolean isEqual(Payload a, Payload b) {
    String aEnc = a.getMetadataOrDefault(METADATA_ENCODING_KEY, ByteString.EMPTY).toStringUtf8();
    String bEnc = b.getMetadataOrDefault(METADATA_ENCODING_KEY, ByteString.EMPTY).toStringUtf8();
    return aEnc.equals(bEnc) && a.getData().equals(b.getData());
  }

  public static @Nonnull Map<String, Payload> mergeMemo(
      @Nonnull Map<String, Payload> src, @Nonnull Map<String, Payload> dst) {
    HashMap result = new HashMap(src);
    dst.forEach(
        (k, v) -> {
          // Remove the key if the value is null or encoding is binary/null
          if (v == null || isEqual(v, nullPayload) || isEqual(v, emptyListPayload)) {
            result.remove(k);
            return;
          }
          result.put(k, v);
        });
    return result;
  }
}
