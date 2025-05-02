package io.temporal.internal.async.spi;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides language specific functionality to disassemble method references that could be passed
 * from different languages into temporal code and extract target from them, which is required for
 * correct working of {@link io.temporal.workflow.Async}
 */
public interface MethodReferenceDisassemblyService {
  String KOTLIN = "kotlin";

  /**
   * @param methodReference method reference to extract target from
   * @return target of the method reference {@code methodReference}, null if methodReference is not
   *     recognized by implementation as a method reference (means that it's general purpose lambda)
   */
  @Nullable
  Object getMethodReferenceTarget(@Nonnull Object methodReference);

  /**
   * @return language this service provides an extension or implementation for
   */
  String getLanguageName();
}
