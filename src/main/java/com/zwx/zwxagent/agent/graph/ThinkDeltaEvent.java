package com.zwx.zwxagent.agent.graph;

/**
 * worker 思考阶段的一条文本增量：经 SSE「think-delta」事件推给前端实时展示。
 * 载荷刻意保持小而扁平，序列化后即为前端渲染所需全部字段。
 */
public record ThinkDeltaEvent(String workerKey, String agent, String text) {
}
