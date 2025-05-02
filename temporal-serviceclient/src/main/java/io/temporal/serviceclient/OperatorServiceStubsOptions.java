package io.temporal.serviceclient;

public final class OperatorServiceStubsOptions extends ServiceStubsOptions {
  private static final OperatorServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return new Builder(options);
  }

  public static OperatorServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private OperatorServiceStubsOptions(ServiceStubsOptions serviceStubsOptions) {
    super(serviceStubsOptions);
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {

    private Builder() {}

    private Builder(ServiceStubsOptions options) {
      super(options);
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public OperatorServiceStubsOptions build() {
      return new OperatorServiceStubsOptions(super.build());
    }

    public OperatorServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new OperatorServiceStubsOptions(serviceStubsOptions);
    }
  }
}
