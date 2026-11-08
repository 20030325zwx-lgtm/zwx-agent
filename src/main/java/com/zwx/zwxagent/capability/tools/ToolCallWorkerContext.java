package com.zwx.zwxagent.capability.tools;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identifies the worker on the thread that invokes a tool callback.
 * 整改 A2：同一线程内每次工具调用分配递增序号，与 workerKey 组成 invocation_key——
 * 图步骤整体重放时序号序列一致（重放幂等），同步骤内的有意重复调用获得新身份。
 */
public final class ToolCallWorkerContext {
    private static final ThreadLocal<String> WORKER = new ThreadLocal<>();
    private static final ThreadLocal<AtomicInteger> CALL_ORDINAL = ThreadLocal.withInitial(() -> new AtomicInteger());

    private ToolCallWorkerContext() { }

    public static void set(String workerKey) {
        WORKER.set(workerKey);
        CALL_ORDINAL.get().set(0);
    }

    public static String current() { return WORKER.get(); }

    /** 当前线程内的下一个调用身份段（workerKey 由调用方拼接）；无 worker 上下文时返回 null。 */
    public static String nextInvocationKey() {
        String workerKey = WORKER.get();
        if (workerKey == null) return null;
        return workerKey + "#call-" + CALL_ORDINAL.get().incrementAndGet();
    }

    public static void clear() {
        WORKER.remove();
        CALL_ORDINAL.remove();
    }

    /** 跨线程并行执行工具时的身份快照：workerKey + 主线程已预分配的调用序号。 */
    public record WorkerSnapshot(String workerKey, int ordinal) {
    }

    /** 主线程捕获当前 worker 身份（子线程并行执行工具前调用）。 */
    public static WorkerSnapshot capture() {
        return new WorkerSnapshot(WORKER.get(), CALL_ORDINAL.get().get());
    }

    /** 子线程恢复身份并固定调用序号（并行场景下序号由主线程按调用顺序预分配，保证重放幂等）。 */
    public static void restore(WorkerSnapshot snapshot, int ordinalForThisCall) {
        WORKER.set(snapshot.workerKey());
        CALL_ORDINAL.get().set(ordinalForThisCall);
    }
}
