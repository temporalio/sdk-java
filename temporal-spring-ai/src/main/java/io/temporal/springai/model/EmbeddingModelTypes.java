package io.temporal.springai.model;

import java.util.List;

/**
 * Serializable types for EmbeddingModel activity communication.
 *
 * <p>These records are used to pass data between workflows and the EmbeddingModelActivity, ensuring
 * all data can be serialized by Temporal's data converter.
 */
public final class EmbeddingModelTypes {

  private EmbeddingModelTypes() {}

  /**
   * Input for embedding a single text.
   *
   * @param text the text to embed
   */
  public record EmbedTextInput(String text) {}

  /**
   * Input for embedding multiple texts.
   *
   * @param texts the texts to embed
   */
  public record EmbedBatchInput(List<String> texts) {}

  /**
   * Output containing a single embedding vector.
   *
   * @param embedding the embedding vector
   */
  public record EmbedOutput(List<Double> embedding) {}

  /**
   * Output containing multiple embedding vectors.
   *
   * @param embeddings the embedding vectors, one per input text
   * @param metadata additional metadata about the embeddings
   */
  public record EmbedBatchOutput(List<EmbeddingResult> embeddings, EmbeddingMetadata metadata) {}

  /**
   * A single embedding result.
   *
   * @param index the index in the original input list
   * @param embedding the embedding vector
   */
  public record EmbeddingResult(int index, List<Double> embedding) {}

  /**
   * Metadata about the embedding operation.
   *
   * @param model the model used for embedding
   * @param totalTokens total tokens processed
   * @param dimensions the dimensionality of the embeddings
   */
  public record EmbeddingMetadata(String model, Integer totalTokens, Integer dimensions) {}

  /**
   * Output containing embedding model dimensions.
   *
   * @param dimensions the number of dimensions in the embedding vectors
   */
  public record DimensionsOutput(int dimensions) {}
}
