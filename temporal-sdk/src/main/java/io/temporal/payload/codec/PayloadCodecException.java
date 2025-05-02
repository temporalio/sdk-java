package io.temporal.payload.codec;

public class PayloadCodecException extends RuntimeException {
  public PayloadCodecException(String message) {
    super(message);
  }

  public PayloadCodecException(String message, Throwable cause) {
    super(message, cause);
  }

  public PayloadCodecException(Throwable cause) {
    super(cause);
  }
}
