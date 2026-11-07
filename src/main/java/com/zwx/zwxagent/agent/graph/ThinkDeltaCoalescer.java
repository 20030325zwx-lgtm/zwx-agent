package com.zwx.zwxagent.agent.graph;

import java.util.function.Consumer;

/**
 * 思考增量合流器：按「≥120 字符或间隔 ≥250ms」把逐 token 增量合并为一条事件再下发。
 * SSE 历史数组对每次新增做 O(n) 摘头修剪，逐 token 直发会放大成性能热点，故必须合流。
 * 线程安全：accept/flush 均在 worker 线程调用（单 worker 单线程），仍以 synchronized 兜底。
 */
final class ThinkDeltaCoalescer implements Consumer<String> {

    private static final int FLUSH_CHAR_THRESHOLD = 120;
    private static final long FLUSH_INTERVAL_MILLIS = 250;

    private final Consumer<String> target;
    private final StringBuilder buffer = new StringBuilder();
    private long lastFlush = System.currentTimeMillis();

    ThinkDeltaCoalescer(Consumer<String> target) {
        this.target = target;
    }

    @Override
    public void accept(String chunk) {
        if (chunk == null) return;
        if (chunk.isEmpty()) {
            // 空串 = 上游的冲刷信号（一轮 think 结束），立即下发已攒内容
            flush();
            return;
        }
        synchronized (this) {
            buffer.append(chunk);
            if (buffer.length() >= FLUSH_CHAR_THRESHOLD
                    || System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MILLIS) {
                flushLocked();
            }
        }
    }

    /** 把残余缓冲强制下发（worker 结束时调用）。 */
    void flush() {
        synchronized (this) {
            flushLocked();
        }
    }

    private void flushLocked() {
        if (buffer.length() == 0) return;
        String text = buffer.toString();
        buffer.setLength(0);
        lastFlush = System.currentTimeMillis();
        target.accept(text);
    }
}
