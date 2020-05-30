package io.temporal.failure;

import static io.temporal.failure.FailureConverter.JAVA_SDK;

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.DataConverterUtils;
import io.temporal.proto.failure.Failure;

public abstract class RemoteException extends TemporalException {

  private final String failureSource;
  private final String failureStackTrace;

  protected RemoteException(Failure failure, Exception cause) {
    super(failure.getMessage(), cause);
    this.failureSource = failure.getSource();
    this.failureStackTrace = failure.getStackTrace();
    if (JAVA_SDK.equals(this.failureSource)) {
      setStackTrace(DataConverterUtils.parseStackTrace(this.failureStackTrace));
    }
  }

  protected RemoteException(String message, Failure cause, DataConverter dataConverter) {
    super(message, FailureConverter.failureToException(cause, dataConverter));
    this.failureSource = JAVA_SDK;
    this.failureStackTrace = "";
  }

  protected RemoteException(Failure failure) {
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
