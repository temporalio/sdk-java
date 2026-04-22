package io.temporal.springai.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.springai.model.VectorStoreTypes;

/**
 * Temporal activity interface for Spring AI VectorStore operations.
 *
 * <p>This activity wraps Spring AI's {@link org.springframework.ai.vectorstore.VectorStore}, making
 * vector database operations durable and retriable within Temporal workflows.
 *
 * <p>Example usage in a workflow:
 *
 * <pre>{@code
 * VectorStoreActivity vectorStore = Workflow.newActivityStub(
 *     VectorStoreActivity.class,
 *     ActivityOptions.newBuilder()
 *         .setStartToCloseTimeout(Duration.ofMinutes(5))
 *         .build());
 *
 * // Add documents
 * vectorStore.addDocuments(new AddDocumentsInput(documents));
 *
 * // Search
 * SearchOutput results = vectorStore.similaritySearch(new SearchInput("query", 10));
 * }</pre>
 */
@ActivityInterface
public interface VectorStoreActivity {

  /**
   * Adds documents to the vector store.
   *
   * <p>If the documents don't have pre-computed embeddings, the vector store will use its
   * configured EmbeddingModel to generate them.
   *
   * @param input the documents to add
   */
  @ActivityMethod
  void addDocuments(VectorStoreTypes.AddDocumentsInput input);

  /**
   * Deletes documents from the vector store by their IDs.
   *
   * @param input the IDs of documents to delete
   */
  @ActivityMethod
  void deleteByIds(VectorStoreTypes.DeleteByIdsInput input);

  /**
   * Performs a similarity search in the vector store.
   *
   * @param input the search parameters
   * @return the search results with similarity scores
   */
  @ActivityMethod
  VectorStoreTypes.SearchOutput similaritySearch(VectorStoreTypes.SearchInput input);
}
