package io.temporal.internal.common;

import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import javax.annotation.Nullable;

public class FailureUtils {
  private FailureUtils() {}

  public static boolean isBenignApplicationFailure(@Nullable Throwable t) {
    if (t instanceof ApplicationFailure
        && ((ApplicationFailure) t).getCategory() == ApplicationErrorCategory.BENIGN) {
      return true;
    }
    return false;
  }

  public static boolean isBenignApplicationFailure(@Nullable Failure failure) {
    if (failure != null
        && failure.getApplicationFailureInfo() != null
        && FailureUtils.categoryFromProto(failure.getApplicationFailureInfo().getCategory())
            == ApplicationErrorCategory.BENIGN) {
      return true;
    }
    return false;
  }

  public static ApplicationErrorCategory categoryFromProto(
      io.temporal.api.enums.v1.ApplicationErrorCategory protoCategory) {
    if (protoCategory == null) {
      return ApplicationErrorCategory.UNSPECIFIED;
    }
    switch (protoCategory) {
      case APPLICATION_ERROR_CATEGORY_BENIGN:
        return ApplicationErrorCategory.BENIGN;
      case APPLICATION_ERROR_CATEGORY_UNSPECIFIED:
      case UNRECOGNIZED:
      default:
        // Fallback unrecognized or unspecified proto values as UNSPECIFIED
        return ApplicationErrorCategory.UNSPECIFIED;
    }
  }

  public static io.temporal.api.enums.v1.ApplicationErrorCategory categoryToProto(
      io.temporal.failure.ApplicationErrorCategory category) {
    switch (category) {
      case BENIGN:
        return io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN;
      case UNSPECIFIED:
      default:
        // Fallback to UNSPECIFIED for unknown values
        return io.temporal.api.enums.v1.ApplicationErrorCategory
            .APPLICATION_ERROR_CATEGORY_UNSPECIFIED;
    }
  }
}
