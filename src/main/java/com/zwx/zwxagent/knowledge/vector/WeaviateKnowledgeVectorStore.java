package com.zwx.zwxagent.knowledge.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.collections.CollectionHandle;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.data.InsertManyResponse;
import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.Hybrid;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.NearVector;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weaviate 实现（服务端 / 本地开发）：
 * <ul>
 *   <li>向量检索：应用侧 DashScope 向量化，nearVector + where 过滤，score = 1 - cosine distance
 *       （与 pgvector 的 cosine_distance → similarity 换算一致，阈值语义不变）；</li>
 *   <li>词法通道：原生 BM25（text 属性按配置分词，默认 gse 中文）；</li>
 *   <li>混合召回：原生 Hybrid Search（FusionType.RANKED = 服务端 RRF k=60，alpha 可配），
 *       替代迁移前 Java 侧 RRF，单次往返完成向量 + BM25 融合；</li>
 *   <li>运维操作：原生 where 过滤删除、逐对象属性 PATCH（生命周期翻转）、chunkIndex 排序取回。</li>
 * </ul>
 * 过滤条件由 {@link WeaviateFilterConverter} 从 Spring AI 表达式树翻译（服务端预过滤，
 * 不存在 pgvector「HNSW 先取邻域再过滤导致欠填充」的问题）。
 *
 * <p>客户端惰性连接：Weaviate 不可用不影响应用启动，检索期失败由既有降级路径
 * （KnowledgeRetrievalOutcome.FAILED）兜底。</p>
 */
public class WeaviateKnowledgeVectorStore implements KnowledgeVectorStore {

    private static final Logger log = LoggerFactory.getLogger(WeaviateKnowledgeVectorStore.class);

    private static final String TEXT_PROPERTY = "text";
    private static final String EXTRA_META_PROPERTY = "extraMeta";

    /** schema 白名单（WeaviateSchemaManager 定义）：等值过滤面文本属性。 */
    private static final Set<String> TEXT_KEYS = Set.of(
            "tenantId", "agentKey", "lifecycleStatus", "documentId", "parentDocumentId", "logicalKey",
            "parentLogicalKey", "sectionPath", "sourceType", "blockId", "filename", "objectKey",
            "regionLabel", "tableCaption", "caption", "bbox", "figureImages", "figureCaption",
            "figureType", "parseQuality", "chunking", "structuredContentType", "parser", "documentType", "chunkRole");

    /** 排序/范围过滤面 int 属性（chunkIndex 排序、pageNumber 预览等）。 */
    private static final Set<String> INT_KEYS = Set.of(
            "chunkIndex", "pageNumber", "rowStart", "rowEnd", "versionNo", "figureFragments",
            "chunkSchemaVersion", "previousChunkIndex", "doclingChunkIndex", "tokenCount");

    private static final Set<String> BOOL_KEYS = Set.of("figureTruncated");

    private final EmbeddingModel embeddingModel;
    private final WeaviateSettings settings;
    private final WeaviateSchemaManager schemaManager;
    private final ObjectMapper objectMapper;
    private volatile WeaviateClient client;

    /** 查询向量缓存：同一次检索里 hybrid 与 nearVector 复用同一向量（与迁移前单次 embedding 对齐）。 */
    private final Map<String, float[]> queryVectorCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > 128;
                }
            });

    public WeaviateKnowledgeVectorStore(EmbeddingModel embeddingModel, WeaviateSettings settings,
                                        WeaviateSchemaManager schemaManager, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.settings = settings;
        this.schemaManager = schemaManager;
        this.objectMapper = objectMapper;
    }

    /** 启动期预热入口：建连 + 幂等建 Collection；失败抛异常由调用方降级。 */
    public void ensureReady() {
        handle();
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        var handle = handle();        // 只嵌入正文（与 DashScope metadata-mode=NONE 语义一致，且对 Ollama 等任意 provider 都成立）；
        // 调用方（DocumentService.storeVectors）已按 embed API 单次上限 25 条分批。
        List<String> texts = documents.stream()
                .map(document -> document.getText() == null ? "" : document.getText()).toList();
        List<float[]> embeddings = embeddingModel.embed(texts);
        List<WeaviateObject<Map<String, Object>>> objects = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            final Document document = documents.get(i);
            final float[] vector = embeddings.get(i);
            objects.add(WeaviateObject.of(builder -> builder
                    .uuid(WeaviateUuid.of(document.getId()))
                    .properties(toProperties(document))
                    .vectors(io.weaviate.client6.v1.api.collections.Vectors.of(vector))));
        }
        InsertManyResponse response = handle.data.insertMany(objects);
        List<String> failures = new ArrayList<>(response.errors() == null ? List.of() : response.errors());
        if (response.responses() != null) {
            response.responses().stream()
                    .filter(item -> !item.successful())
                    .forEach(item -> failures.add(item.uuid() + ": " + item.error()));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Weaviate batch insert failed: " + String.join("; ", failures));
        }
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) return;
        var response = handle().data.deleteMany(idList.toArray(String[]::new));
        if (response.failed() > 0) {
            log.warn("Weaviate delete by ids: {} 条失败 / {} 条成功", response.failed(), response.successful());
        }
    }

    @Override
    public void delete(Expression filterExpression) {
        handle().data.deleteMany(WeaviateFilterConverter.convert(filterExpression));
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        var handle = handle();
        Filter where = request.hasFilterExpression()
                ? WeaviateFilterConverter.convert(request.getFilterExpression()) : null;
        double threshold = request.getSimilarityThreshold();
        NearVector near = NearVector.of(queryVector(request.getQuery()), nearVector -> {
            // score = 1 - distance：阈值换算为服务端距离上限（与 pgvector cosine_distance 阈值语义一致）
            if (threshold > 0) nearVector.distance((float) (1.0 - threshold));
            nearVector.limit(request.getTopK());
            if (where != null) nearVector.filters(where);
            nearVector.returnMetadata(Metadata.DISTANCE);
            return nearVector;
        });
        QueryResponse<Map<String, Object>> response = handle.query.nearVector(near);
        return toDocuments(response, metadata -> {
            Float distance = metadata == null ? null : metadata.distance();
            return distance == null ? null : 1.0 - distance.doubleValue();
        });
    }

    @Override
    public List<Document> hybridSearch(String tenantId, String agentKey, String query,
                                       int limit, double vectorSimilarityThreshold) {
        if (query == null || query.isBlank()) return List.of();
        var handle = handle();
        Filter where = scopeAndLifecycle(tenantId, agentKey);
        float[] vector = queryVector(query);
        Hybrid hybrid = Hybrid.of(query, builder -> {
            builder.nearVector(NearVector.of(vector))
                    .queryProperties(TEXT_PROPERTY)
                    .alpha((float) settings.hybridAlpha())
                    .fusionType(Hybrid.FusionType.RANKED)
                    .limit(limit)
                    .filters(where)
                    .returnMetadata(Metadata.SCORE);
            // 不设 maxVectorDistance：实测（weaviate 1.39）ranked fusion + 距离上限会把结果清空，
            // 且原生 hybrid 无逐对象阈值语义——阈值仅由 pg 实现生效（见 KnowledgeVectorStore#hybridSearch javadoc）
            return builder;
        });
        QueryResponse<Map<String, Object>> response = handle.query.hybrid(hybrid);
        return toDocuments(response, metadata -> metadata == null ? null : toDouble(metadata.score()));
    }

    @Override
    public List<Document> bm25Search(String tenantId, String agentKey, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        var handle = handle();
        QueryResponse<Map<String, Object>> response = handle.query.bm25(query, builder -> builder
                .queryProperties(TEXT_PROPERTY)
                .limit(limit)
                .filters(scopeAndLifecycle(tenantId, agentKey))
                .returnMetadata(Metadata.SCORE));
        // 词法通道 Document.score 置空：与 pgvector 词法通道一致（RRF 只用排名，trace 的 lexicalScore 由上层现算）
        return toDocuments(response, metadata -> null);
    }

    @Override
    public List<Document> fetchSiblings(String tenantId, String agentKey, String documentId,
                                        String sectionPath, int limit) {
        var handle = handle();
        Filter where = Filter.and(
                scopeAndLifecycle(tenantId, agentKey),
                Filter.property("documentId").eq(documentId),
                Filter.property("sectionPath").eq(sectionPath));
        return fetchSorted(handle, where, limit);
    }

    @Override
    public List<Document> listByDocument(String tenantId, String agentKey, String documentId) {
        var handle = handle();
        Filter where = Filter.and(
                Filter.property("tenantId").eq(tenantId),
                Filter.property("agentKey").eq(agentKey),
                Filter.property("documentId").eq(documentId));
        return fetchSorted(handle, where, settings.listLimit());
    }

    @Override
    public java.util.Optional<Document> findById(String tenantId, String agentKey, String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return java.util.Optional.empty();
        var handle = handle();
        return handle.query.fetchObjectById(WeaviateUuid.of(chunkId))
                .map(object -> toDocument(object, null))
                .filter(document -> tenantId.equals(String.valueOf(document.getMetadata().get("tenantId")))
                        && agentKey.equals(String.valueOf(document.getMetadata().get("agentKey"))));
    }

    @Override
    public void updateLifecycleStatus(String documentId, String lifecycleStatus) {
        var handle = handle();
        Filter where = Filter.and(
                Filter.property("documentId").eq(documentId),
                Filter.property("lifecycleStatus").ne(lifecycleStatus));
        List<WeaviateObject<Map<String, Object>>> targets = handle.query
                .fetchObjects(fetch -> fetch.filters(where).limit(settings.listLimit())).objects();
        for (WeaviateObject<Map<String, Object>> target : targets) {
            try {
                handle.data.update(target.uuid(), update -> update
                        .properties(Map.of("lifecycleStatus", lifecycleStatus)));
            } catch (Exception exception) {
                throw new IllegalStateException("Weaviate lifecycle flip failed for " + target.uuid()
                        + ": " + exception.getMessage(), exception);
            }
        }
        if (!targets.isEmpty()) {
            log.info("Weaviate lifecycle flip: documentId={}, status={}, objects={}",
                    documentId, lifecycleStatus, targets.size());
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        handle().data.deleteMany(Filter.property("documentId").eq(documentId));
    }

    @Override
    public void deleteByDocumentId(String documentId, String tenantId, String agentKey) {
        handle().data.deleteMany(Filter.and(
                Filter.property("documentId").eq(documentId),
                Filter.property("tenantId").eq(tenantId),
                Filter.property("agentKey").eq(agentKey)));
    }

    private List<Document> fetchSorted(CollectionHandle<Map<String, Object>> handle, Filter where, int limit) {
        QueryResponse<Map<String, Object>> response = handle.query.fetchObjects(fetch -> fetch
                .filters(where)
                .limit(limit)
                .sort(SortBy.property("chunkIndex")));
        return toDocuments(response, metadata -> null);
    }

    private Filter scopeAndLifecycle(String tenantId, String agentKey) {
        return Filter.and(
                Filter.property("tenantId").eq(tenantId),
                Filter.property("agentKey").eq(agentKey),
                Filter.property("lifecycleStatus").eq("ACTIVE"));
    }

    private List<Document> toDocuments(QueryResponse<Map<String, Object>> response,
                                       java.util.function.Function<io.weaviate.client6.v1.api.collections.query.QueryMetadata, Double> scoreOf) {
        if (response == null || response.objects() == null) return List.of();
        List<Document> documents = new ArrayList<>(response.objects().size());
        for (WeaviateObject<Map<String, Object>> object : response.objects()) {
            documents.add(toDocument(object, scoreOf.apply(object.queryMetadata())));
        }
        return documents;
    }

    private Document toDocument(WeaviateObject<Map<String, Object>> object, Double score) {
        Map<String, Object> properties = object.properties() == null ? Map.of() : object.properties();
        Object text = properties.get(TEXT_PROPERTY);
        Document.Builder builder = Document.builder()
                .id(object.uuid())
                .text(text == null ? "" : String.valueOf(text))
                .metadata(toMetadata(properties));
        if (score != null) builder.score(score);
        return builder.build();
    }

    /** 写入：Document.metadata → Weaviate 白名单属性 + extraMeta JSON（未入白名单的键）。 */
    private Map<String, Object> toProperties(Document document) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TEXT_PROPERTY, document.getText() == null ? "" : document.getText());
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.getMetadata().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null || TEXT_PROPERTY.equals(key) || EXTRA_META_PROPERTY.equals(key)) continue;
            if (TEXT_KEYS.contains(key)) {
                props.put(key, String.valueOf(value));
            } else if (INT_KEYS.contains(key)) {
                Integer intValue = toInt(value);
                if (intValue != null) props.put(key, intValue);
                else extra.put(key, value);
            } else if (BOOL_KEYS.contains(key)) {
                Boolean boolValue = toBool(value);
                if (boolValue != null) props.put(key, boolValue);
                else extra.put(key, value);
            } else {
                extra.put(key, value);
            }
        }
        if (!extra.isEmpty()) {
            try {
                props.put(EXTRA_META_PROPERTY, objectMapper.writeValueAsString(extra));
            } catch (Exception exception) {
                log.warn("extraMeta 序列化失败，丢弃 {} 个非白名单元数据键：{}", extra.size(), exception.getMessage());
            }
        }
        return props;
    }

    /** 读取：Weaviate 属性 → Document.metadata；extraMeta JSON 展开合并（白名单键优先）。 */
    private Map<String, Object> toMetadata(Map<String, Object> properties) {
        Map<String, Object> metadata = new HashMap<>();
        String extraJson = null;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (TEXT_PROPERTY.equals(key)) continue;
            if (EXTRA_META_PROPERTY.equals(key)) {
                extraJson = entry.getValue() == null ? null : String.valueOf(entry.getValue());
                continue;
            }
            // 未设置的属性可能以 null 返回；Spring AI Document 拒绝 null metadata 值
            if (entry.getValue() == null) continue;
            metadata.put(key, normalize(entry.getValue()));
        }
        if (extraJson != null && !extraJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> extra = objectMapper.readValue(extraJson, Map.class);
                extra.forEach(metadata::putIfAbsent);
            } catch (Exception exception) {
                log.warn("extraMeta 解析失败：{}", exception.getMessage());
            }
        }
        return metadata;
    }

    private Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue() == Math.rint(number.doubleValue())
                    && Math.abs(number.doubleValue()) < Integer.MAX_VALUE ? (Object) number.intValue() : value;
        }
        return value;
    }

    private Integer toInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean toBool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(string.trim())) return Boolean.FALSE;
        }
        return null;
    }

    private Double toDouble(Float value) {
        return value == null ? null : value.doubleValue();
    }

    private float[] queryVector(String query) {
        String key = query == null ? "" : query.trim();
        float[] cached = queryVectorCache.get(key);
        if (cached != null) return cached;
        float[] vector = embeddingModel.embed(key);
        queryVectorCache.put(key, vector);
        return vector;
    }

    private CollectionHandle<Map<String, Object>> handle() {
        WeaviateClient current = client();
        try {
            schemaManager.ensureCollection(current);
        } catch (Exception exception) {
            throw new IllegalStateException("Weaviate schema ensure failed: " + exception.getMessage(), exception);
        }
        return current.collections.use(settings.collection());
    }

    /** 惰性建连：构造时不触碰网络，首次使用才连接（Weaviate 不可用不阻断启动）。 */
    private WeaviateClient client() {
        WeaviateClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = WeaviateClient.connectToCustom(config -> config
                            .scheme(settings.scheme())
                            .httpHost(settings.host())
                            .httpPort(settings.httpPort())
                            .grpcHost(settings.host())
                            .grpcPort(settings.grpcPort())
                            .timeout(5, settings.timeoutSeconds(), settings.timeoutSeconds()));
                    client = current;
                    log.info("Weaviate client connected: {}://{} (http:{}, grpc:{}), collection={}, tokenization={}, hybridAlpha={}",
                            settings.scheme(), settings.host(), settings.httpPort(), settings.grpcPort(),
                            settings.collection(), settings.bm25Tokenization(), settings.hybridAlpha());
                }
            }
        }
        return current;
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        WeaviateClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }
}
