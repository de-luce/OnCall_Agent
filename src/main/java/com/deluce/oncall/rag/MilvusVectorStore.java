package com.deluce.oncall.rag;

import com.deluce.oncall.config.MilvusProperties;
import com.deluce.oncall.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Milvus 向量库：与 Python 版共用 collection schema（id / text / source / embedding）。
 */
@Component
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final MilvusServiceClient client;
    private final EmbeddingModel embeddingModel;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;
    private final String collectionName;

    public MilvusVectorStore(
            EmbeddingModel embeddingModel,
            MilvusProperties milvusProperties,
            RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.milvusProperties = milvusProperties;
        this.ragProperties = ragProperties;
        this.collectionName = milvusProperties.collection();
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withUri(milvusProperties.uri())
                        .withAuthorization(milvusProperties.user(), milvusProperties.password())
                        .build()
        );
        ensureCollection();
        loadCollection();
        log.info("Connected to Milvus at {} collection={}", milvusProperties.uri(), collectionName);
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();

        for (Document document : documents) {
            ids.add(UUID.randomUUID().toString());
            texts.add(document.getText());
            Object source = document.getMetadata().get("source");
            sources.add(source != null ? source.toString() : "");
            vectors.add(toFloatList(embeddingModel.embed(document)));
        }

        insertRows(ids, texts, sources, vectors);
    }

    public int addChunks(List<String> texts, String source) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();

        List<float[]> embeddings = embeddingModel.embed(texts);
        for (int i = 0; i < texts.size(); i++) {
            ids.add(UUID.randomUUID().toString());
            sources.add(source);
            vectors.add(toFloatList(embeddings.get(i)));
        }
        insertRows(ids, texts, sources, vectors);
        return texts.size();
    }

    @Override
    public void delete(List<String> idList) {
        // 当前业务未使用按 id 删除
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        // 当前业务未使用表达式删除
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        int topK = request.getTopK() > 0 ? request.getTopK() : ragProperties.topK();
        float[] queryEmbedding = embeddingModel.embed(query);

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("embedding")
                .withVectors(List.of(toFloatList(queryEmbedding)))
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("text", "source"))
                .build();

        R<SearchResults> response = client.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus search failed: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<Document> documents = new ArrayList<>();
        if (wrapper.getRowRecords().isEmpty()) {
            return documents;
        }
        for (SearchResultsWrapper.IDScore idScore : wrapper.getIDScore(0)) {
            Object text = idScore.getFieldValues().get("text");
            Object source = idScore.getFieldValues().get("source");
            documents.add(new Document(
                    text != null ? text.toString() : "",
                    Map.of(
                            "source", source != null ? source.toString() : "",
                            "score", idScore.getScore()
                    )
            ));
        }
        return documents;
    }

    @Override
    public Optional<Object> getNativeClient() {
        return Optional.of(client);
    }

    public int countBySource(String source) {
        String expr = "source == \"" + escapeExprValue(source) + "\"";
        QueryParam queryParam = QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(List.of("id"))
                .withLimit(16384L)
                .build();
        R<io.milvus.grpc.QueryResults> response = client.query(queryParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus count_by_source failed for {}: {}", source, response.getMessage());
            return 0;
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        return wrapper.getRowRecords().size();
    }

    private void insertRows(
            List<String> ids,
            List<String> texts,
            List<String> sources,
            List<List<Float>> vectors) {
        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("id", ids),
                new InsertParam.Field("text", texts),
                new InsertParam.Field("source", sources),
                new InsertParam.Field("embedding", vectors)
        );
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();
        R<MutationResult> response = client.insert(insertParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus insert failed: " + response.getMessage());
        }
    }

    private void ensureCollection() {
        R<Boolean> hasCollection = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build()
        );
        if (hasCollection.getData() != null && hasCollection.getData()) {
            return;
        }

        int dim = milvusProperties.embeddingDimension();
        List<FieldType> fields = List.of(
                FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .build(),
                FieldType.newBuilder()
                        .withName("text")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build(),
                FieldType.newBuilder()
                        .withName("source")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(512)
                        .build(),
                FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dim)
                        .build()
        );

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("OnCall knowledge base")
                .withFieldTypes(fields)
                .build();
        R<?> createResponse = client.createCollection(createParam);
        if (createResponse.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus create collection failed: " + createResponse.getMessage());
        }

        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(IndexType.AUTOINDEX)
                .withMetricType(MetricType.COSINE)
                .build();
        R<?> indexResponse = client.createIndex(indexParam);
        if (indexResponse.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus create index failed: " + indexResponse.getMessage());
        }
        log.info("Created Milvus collection {} (dim={})", collectionName, dim);
    }

    private void loadCollection() {
        R<?> response = client.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build()
        );
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus load collection failed: " + response.getMessage());
        }
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    private static String escapeExprValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @PreDestroy
    void shutdown() {
        client.close();
    }
}
