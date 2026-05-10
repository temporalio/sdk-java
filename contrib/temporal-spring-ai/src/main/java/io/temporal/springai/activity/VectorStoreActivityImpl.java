package io.temporal.springai.activity;

import io.temporal.springai.model.VectorStoreTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

/**
 * Implementation of {@link VectorStoreActivity} that delegates to a Spring AI {@link VectorStore}.
 *
 * <p>This implementation handles the conversion between Temporal-serializable types ({@link
 * VectorStoreTypes}) and Spring AI types.
 */
public class VectorStoreActivityImpl implements VectorStoreActivity {

  private final VectorStore vectorStore;
  private final FilterExpressionTextParser filterParser = new FilterExpressionTextParser();

  public VectorStoreActivityImpl(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  @Override
  public void addDocuments(VectorStoreTypes.AddDocumentsInput input) {
    List<Document> documents =
        input.documents().stream().map(this::toSpringDocument).collect(Collectors.toList());
    vectorStore.add(documents);
  }

  @Override
  public void deleteByIds(VectorStoreTypes.DeleteByIdsInput input) {
    vectorStore.delete(input.ids());
  }

  @Override
  public VectorStoreTypes.SearchOutput similaritySearch(VectorStoreTypes.SearchInput input) {
    SearchRequest.Builder requestBuilder =
        SearchRequest.builder().query(input.query()).topK(input.topK());

    if (input.similarityThreshold() != null) {
      requestBuilder.similarityThreshold(input.similarityThreshold());
    }

    if (input.filterExpression() != null && !input.filterExpression().isBlank()) {
      requestBuilder.filterExpression(filterParser.parse(input.filterExpression()));
    }

    List<Document> results = vectorStore.similaritySearch(requestBuilder.build());

    List<VectorStoreTypes.SearchResult> searchResults =
        results.stream()
            .map(doc -> new VectorStoreTypes.SearchResult(fromSpringDocument(doc), doc.getScore()))
            .collect(Collectors.toList());

    return new VectorStoreTypes.SearchOutput(searchResults);
  }

  private Document toSpringDocument(VectorStoreTypes.Document doc) {
    Document.Builder builder = Document.builder().id(doc.id()).text(doc.text());

    if (doc.metadata() != null && !doc.metadata().isEmpty()) {
      builder.metadata(new HashMap<>(doc.metadata()));
    }

    return builder.build();
  }

  private VectorStoreTypes.Document fromSpringDocument(Document doc) {
    // Convert metadata, handling potential non-serializable values
    Map<String, Object> metadata = new HashMap<>();
    if (doc.getMetadata() != null) {
      for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
        Object value = entry.getValue();
        // Only include serializable primitive types
        if (value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
          metadata.put(entry.getKey(), value);
        } else {
          metadata.put(entry.getKey(), value.toString());
        }
      }
    }

    return new VectorStoreTypes.Document(
        doc.getId(),
        doc.getText(),
        metadata,
        null // Don't include embedding in results to reduce payload size
        );
  }
}
