package io.temporal.internal.common.env;

class SystemEnvironmentVariablesProvider implements EnvironmentVariablesProvider {
  public static final EnvironmentVariablesProvider INSTANCE =
      new SystemEnvironmentVariablesProvider();

  @Override
  public String getenv(String name) {
    return System.getenv(name);
  }
}
