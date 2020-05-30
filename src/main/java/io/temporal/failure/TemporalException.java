package io.temporal.failure;

import com.google.common.base.Strings;
import io.temporal.internal.common.DataConverterUtils;
import io.temporal.proto.failure.Failure;

import static io.temporal.failure.FailureConverter.JAVA_SDK;

public abstract class TemporalException extends RuntimeException {

  private final String failureSource;
  private final String failureStackTrace;

  protected TemporalException(Failure failure, Exception cause) {
    super(failure.getMessage(), cause, true, true);
    this.failureSource = failure.getSource();
    this.failureStackTrace = failure.getStackTrace();
    if (JAVA_SDK.equals(this.failureSource)) {
      setStackTrace(DataConverterUtils.parseStackTrace(this.failureStackTrace));
    }
  }

  protected TemporalException(Failure failure) {
    this(failure, null);
  }

  public String getFailureSource() {
    return failureSource;
  }

  public String getFailureStackTrace() {
    return failureStackTrace;
  }

  @Override
  public String toString() {
    String s = getClass().getName();
    if (failureSource.equals(JAVA_SDK) || Strings.isNullOrEmpty(failureStackTrace)) {
      return super.toString();
    }
    return s + ": " + getMessage() + "\n" + failureStackTrace;
  }
}
