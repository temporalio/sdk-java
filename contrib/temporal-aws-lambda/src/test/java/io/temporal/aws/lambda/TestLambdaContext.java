package io.temporal.aws.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.util.function.IntSupplier;

final class TestLambdaContext implements Context {
  private static final LambdaLogger NOOP_LOGGER =
      new LambdaLogger() {
        @Override
        public void log(String message) {}

        @Override
        public void log(byte[] message) {}
      };

  private final IntSupplier remainingTimeMillis;
  private final String awsRequestId;
  private final String invokedFunctionArn;

  TestLambdaContext(int remainingTimeMillis) {
    this(remainingTimeMillis, "request-id", "function-arn");
  }

  TestLambdaContext(int remainingTimeMillis, String awsRequestId, String invokedFunctionArn) {
    this(() -> remainingTimeMillis, awsRequestId, invokedFunctionArn);
  }

  TestLambdaContext(IntSupplier remainingTimeMillis) {
    this(remainingTimeMillis, "request-id", "function-arn");
  }

  TestLambdaContext(
      IntSupplier remainingTimeMillis, String awsRequestId, String invokedFunctionArn) {
    this.remainingTimeMillis = remainingTimeMillis;
    this.awsRequestId = awsRequestId;
    this.invokedFunctionArn = invokedFunctionArn;
  }

  @Override
  public String getAwsRequestId() {
    return awsRequestId;
  }

  @Override
  public String getLogGroupName() {
    return "log-group";
  }

  @Override
  public String getLogStreamName() {
    return "log-stream";
  }

  @Override
  public String getFunctionName() {
    return "function";
  }

  @Override
  public String getFunctionVersion() {
    return "1";
  }

  @Override
  public String getInvokedFunctionArn() {
    return invokedFunctionArn;
  }

  @Override
  public CognitoIdentity getIdentity() {
    return null;
  }

  @Override
  public ClientContext getClientContext() {
    return null;
  }

  @Override
  public int getRemainingTimeInMillis() {
    return remainingTimeMillis.getAsInt();
  }

  @Override
  public int getMemoryLimitInMB() {
    return 128;
  }

  @Override
  public LambdaLogger getLogger() {
    return NOOP_LOGGER;
  }
}
