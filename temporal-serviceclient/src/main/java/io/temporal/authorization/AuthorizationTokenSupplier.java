package io.temporal.authorization;

/**
 * Supplies tokens that will be sent to the Temporal server to perform authorization.
 * Implementations have to be thread-safe.
 *
 * <p>The default JWT ClaimMapper expects authorization tokens to be in the following format:
 *
 * <p>{@code Bearer <token>}
 *
 * <p>{@code <token>} Must be the Base64 url-encoded value of the token.
 *
 * @see <a href="https://docs.temporal.io/docs/server/security/#format-of-json-web-tokens">Format of
 *     JWT</a>
 */
public interface AuthorizationTokenSupplier {
  /**
   * @return token to be passed in authorization header
   */
  String supply();
}
