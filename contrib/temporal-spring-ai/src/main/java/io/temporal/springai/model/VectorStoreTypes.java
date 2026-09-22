package io.temporal.springai.model;

import java.util.List;
import java.util.Map;

/**
 * Serializable types for VectorStore activity communication.
 *
 * <p>These records are used to pass data between workflows and the VectorStoreActivity, ensuring
 * all data can be serialized by Temporal's data converter.
 */
public final class VectorStoreTypes {

  private VectorStoreTypes() {}

  /**
   * Serializable representation of a document for vector storage.
   *
   * @param id unique identifier for the document
   * @param text the text content of the document
   * @param metadata additional metadata associated with the document
   * @param embedding pre-computed embedding vector (optional, may be computed by the store)
   */
  public record Document(
      String id, String text, Map<String, Object> metadata, List<Double> embedding) {
    public Document(String id, String text, Map<String, Object> metadata) {
      this(id, text, metadata, null);
    }

    public Document(String id, String text) {
      this(id, text, Map.of(), null);
    }
  }

  /**
   * Input for adding documents to the vector store.
   *
   * @param documents the documents to add
   */
  public record AddDocumentsInput(List<Document> documents) {}

  /**
   * Input for deleting documents by ID.
   *
   * @param ids the document IDs to delete
   */
  public record DeleteByIdsInput(List<String> ids) {}

  /**
   * Input for similarity search.
   *
   * @param query the search query text
   * @param topK maximum number of results to return
   * @param similarityThreshold minimum similarity score (0.0 to 1.0)
   * @param filterExpression optional filter expression for metadata filtering
   */
  public record SearchInput(
      String query, int topK, Double similarityThreshold, String filterExpression) {
    public SearchInput(String query, int topK) {
      this(query, topK, null, null);
    }

    public SearchInput(String query) {
      this(query, 4, null, null);
    }
  }

  /**
   * Output from similarity search.
   *
   * @param documents the matching documents with their similarity scores
   */
  public record SearchOutput(List<SearchResult> documents) {}

  /**
   * A single search result with similarity score.
   *
   * @param document the matched document
   * @param score the similarity score
   */
  public record SearchResult(Document document, Double score) {}
}
