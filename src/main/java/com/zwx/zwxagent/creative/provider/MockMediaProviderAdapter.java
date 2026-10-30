package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内 Mock 适配器（C05，A07–A13）：无网络调用，行为由测试脚本化；
 * 记录 submit/query 调用次数供“至多一次有效提交”断言。生产路径永远不会装配它。
 */
@Component
public class MockMediaProviderAdapter implements MediaProviderAdapter {

    /** 查询脚本：前 N 次 running，随后进入最终状态。 */
    public record QueryScript(int runningCount, QueryState finalState,
                              List<MediaProviderAdapter.Output> outputs, String errorCode) {
    }

    private final Map<String, Deque<SubmitResult>> submitScripts = new ConcurrentHashMap<>();
    private final Map<String, QueryScript> queryScripts = new ConcurrentHashMap<>();
    private volatile QueryScript defaultQueryScript = null;
    private final Map<String, AtomicInteger> submitCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> queryCounts = new ConcurrentHashMap<>();
    private final List<String> eventLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String key() {
        return "mock-text2image";
    }

    /** 预设某个 submissionKey 的提交结果序列（不预设则默认 Accepted）。 */
    public void enqueueSubmitResult(String submissionKey, SubmitResult... results) {
        var deque = new ArrayDeque<SubmitResult>();
        for (SubmitResult result : results) deque.addLast(result);
        submitScripts.put(submissionKey, deque);
    }

    /** 预设某个 providerTaskId 的查询脚本。 */
    public void setQueryScript(String providerTaskId, QueryScript script) {
        queryScripts.put(providerTaskId, script);
    }

    /** 预设未指定脚本的任务的默认查询脚本（如“一直运行中”用于占满在途额度）。 */
    public void setDefaultQueryScript(QueryScript script) {
        this.defaultQueryScript = script;
    }

    public int submitCount(String submissionKey) {
        return submitCounts.getOrDefault(submissionKey, new AtomicInteger()).get();
    }

    public int queryCount(String providerTaskId) {
        return queryCounts.getOrDefault(providerTaskId, new AtomicInteger()).get();
    }

    public int submitCount() {
        return submitCounts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public List<String> eventLog() {
        return List.copyOf(eventLog);
    }

    public void reset() {
        submitScripts.clear();
        queryScripts.clear();
        submitCounts.clear();
        queryCounts.clear();
        eventLog.clear();
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey) {
        submitCounts.computeIfAbsent(submissionKey, ignored -> new AtomicInteger()).incrementAndGet();
        eventLog.add("submit:" + submissionKey);
        var script = submitScripts.get(submissionKey) != null
                ? submitScripts.get(submissionKey) : submitScripts.get("*");
        if (script != null && !script.isEmpty()) {
            SubmitResult result = script.pollFirst();
            if (result.outcome() == SubmitOutcome.ACCEPTED && (result.providerTaskId() == null)) {
                return SubmitResult.accepted("mock-task-" + UUID.randomUUID());
            }
            return result;
        }
        return SubmitResult.accepted("mock-task-" + UUID.randomUUID());
    }

    @Override
    public QueryResult query(ResolvedMediaModel model, String providerTaskId) {
        queryCounts.computeIfAbsent(providerTaskId, ignored -> new AtomicInteger()).incrementAndGet();
        eventLog.add("query:" + providerTaskId);
        var script = queryScripts.get(providerTaskId) != null
                ? queryScripts.get(providerTaskId) : defaultQueryScript;
        if (script == null) return QueryResult.succeeded(List.of(Output.ofBytes(defaultBytes())));
        int count = queryCounts.get(providerTaskId).get();
        if (count <= script.runningCount()) return QueryResult.running();
        return switch (script.finalState()) {
            case SUCCEEDED -> QueryResult.succeeded(script.outputs().isEmpty()
                    ? List.of(Output.ofBytes(defaultBytes())) : script.outputs());
            case FAILED -> QueryResult.failed(script.errorCode(), "mock failure");
            case UNKNOWN -> QueryResult.unknown("mock unknown");
            default -> QueryResult.running();
        };
    }

    private static byte[] defaultBytes() {
        // 合法 PNG 文件头 + 最小数据块，供签名/大小校验使用
        return new byte[]{-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82};
    }
}
