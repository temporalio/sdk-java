package io.temporal.common.converter;

import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.internal.payload.storage.ExternalStorageUnhandledReferenceException;
import org.junit.Test;

/**
 * An external storage reference reaching value deserialization indicates that an SDK integration
 * failed to resolve or reject it at the appropriate boundary.
 */
public class ExternalStorageReferenceGuardTest {

  private final DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();

  @Test
  public void unhandledReferencePayloadThrowsSdkBug() {
    Payload reference =
        Payload.newBuilder()
            .putMetadata(
                EncodingKeys.METADATA_ENCODING_KEY, ByteString.copyFromUtf8("json/protobuf"))
            .putMetadata(
                EncodingKeys.METADATA_MESSAGE_TYPE_KEY,
                ByteString.copyFromUtf8(ExternalStorageReference.getDescriptor().getFullName()))
            .setData(ByteString.copyFromUtf8("{}"))
            .build();

    assertThrows(
            ExternalStorageUnhandledReferenceException.class,
            () -> dataConverter.fromPayload(reference, String.class, String.class));
  }
}
