package io.temporal.serviceclient;

public final class TestServiceStubsOptions extends ServiceStubsOptions {
  private static final TestServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return new Builder(options);
  }

  public static TestServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private TestServiceStubsOptions(ServiceStubsOptions serviceStubsOptions) {
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
    public TestServiceStubsOptions build() {
      return new TestServiceStubsOptions(super.build());
    }

    public TestServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new TestServiceStubsOptions(serviceStubsOptions);
    }
  }
}
