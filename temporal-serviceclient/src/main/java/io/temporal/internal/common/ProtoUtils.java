package io.temporal.internal.common;

import com.google.protobuf.*;

public class ProtoUtils {

  /**
   * This method does exactly what {@link Any#pack(Message)} does. But it doesn't go into reflection
   * to fetch the {@code descriptor}, which allows us to avoid a bunch of Graal reflection configs.
   */
  public static <T extends Message> Any packAny(T details, Descriptors.Descriptor descriptor) {
    return Any.newBuilder()
        .setTypeUrl("type.googleapis.com/" + descriptor.getFullName())
        .setValue(details.toByteString())
        .build();
  }
}
