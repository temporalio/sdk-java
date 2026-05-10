package io.temporal.springai.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.springai.model.EmbeddingModelTypes;

/**
 * Temporal activity interface for Spring AI EmbeddingModel operations.
 *
 * <p>This activity wraps Spring AI's {@link org.springframework.ai.embedding.EmbeddingModel},
 * making embedding generation durable and retriable within Temporal workflows.
 *
 * <p>Example usage in a workflow:
 *
 * <pre>{@code
 * EmbeddingModelActivity embeddingModel = Workflow.newActivityStub(
 *     EmbeddingModelActivity.class,
 *     ActivityOptions.newBuilder()
 *         .setStartToCloseTimeout(Duration.ofMinutes(2))
 *         .build());
 *
 * // Embed single text
 * EmbedOutput result = embeddingModel.embed(new EmbedTextInput("Hello world"));
 * List<Double> vector = result.embedding();
 *
 * // Embed batch
 * EmbedBatchOutput batchResult = embeddingModel.embedBatch(
 *     new EmbedBatchInput(List.of("text1", "text2", "text3")));
 * }</pre>
 */
@ActivityInterface
public interface EmbeddingModelActivity {

  /**
   * Generates an embedding for a single text.
   *
   * @param input the text to embed
   * @return the embedding vector
   */
  @ActivityMethod
  EmbeddingModelTypes.EmbedOutput embed(EmbeddingModelTypes.EmbedTextInput input);

  /**
   * Generates embeddings for multiple texts in a single request.
   *
   * <p>This is more efficient than calling {@link #embed} multiple times when you have multiple
   * texts to embed.
   *
   * @param input the texts to embed
   * @return the embedding vectors with metadata
   */
  @ActivityMethod
  EmbeddingModelTypes.EmbedBatchOutput embedBatch(EmbeddingModelTypes.EmbedBatchInput input);

  /**
   * Returns the dimensionality of the embedding vectors produced by this model.
   *
   * @return the number of dimensions
   */
  @ActivityMethod
  EmbeddingModelTypes.DimensionsOutput dimensions();
}
