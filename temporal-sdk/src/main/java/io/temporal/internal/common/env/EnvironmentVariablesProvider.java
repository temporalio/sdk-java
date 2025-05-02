package io.temporal.internal.common.env;

/**
 * Same contract as {@link System#getenv(String)} This class exists only to allow overriding
 * environment variables for tests
 */
interface EnvironmentVariablesProvider {
  String getenv(String name);
}
