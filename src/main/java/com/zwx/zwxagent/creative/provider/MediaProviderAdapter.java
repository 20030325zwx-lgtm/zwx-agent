package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;

import java.util.List;

/**
 * 媒体供应商适配器契约（总体设计 §7.2）。实现不得保存密钥，不得在调用外留存状态；
 * submit 可能成功但响应丢失时必须返回 Unknown，由调用方进入 RECONCILING。
 */
public interface MediaProviderAdapter {

    /** 适配器标识，对应 media_model_profile.adapter_key。 */
    String key();

    /**
     * 提交生成任务。
     * 返回 Accepted（含上游 taskId）、Completed（同步返回产物）、Rejected（确定拒绝）、Unknown（无法确定）。
     */
    SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey);

    /** 查询上游任务状态与结果（结果 URL 24h 有效，调用方须及时转存）。 */
    QueryResult query(ResolvedMediaModel model, String providerTaskId);

    /** 取消：默认不支持（BR13）；支持者返回 Confirmed/Requested。 */
    default CancelOutcome cancel(ResolvedMediaModel model, String providerTaskId) {
        return CancelOutcome.UNSUPPORTED;
    }

    enum SubmitOutcome { ACCEPTED, COMPLETED, REJECTED, UNKNOWN }

    enum QueryState { RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    enum CancelOutcome { CONFIRMED, REQUESTED, UNSUPPORTED }

    record Output(String url, byte[] bytes, String mimeType) {
        public static Output ofUrl(String url) {
            return new Output(url, null, "image/png");
        }

        public static Output ofBytes(byte[] bytes) {
            return new Output(null, bytes, "image/png");
        }
    }

    record SubmitResult(SubmitOutcome outcome, String providerTaskId, List<Output> outputs,
                        String errorCode, String message, long retryAfterSeconds) {
        public static SubmitResult accepted(String providerTaskId) {
            return new SubmitResult(SubmitOutcome.ACCEPTED, providerTaskId, List.of(), null, null, 0);
        }

        public static SubmitResult completed(List<Output> outputs) {
            return new SubmitResult(SubmitOutcome.COMPLETED, null, outputs, null, null, 0);
        }

        public static SubmitResult rejected(String errorCode, String message) {
            return new SubmitResult(SubmitOutcome.REJECTED, null, List.of(), errorCode, message, 0);
        }

        public static SubmitResult unknown(String message) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, null, List.of(), null, message, 0);
        }

        /** 上游明确限流（未受理）：调用方可按 Retry-After 退避后原样重试提交。 */
        public static SubmitResult throttled(String message, long retryAfterSeconds) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, null, List.of(), "PROVIDER_RATE_LIMITED",
                    message, retryAfterSeconds);
        }
    }

    record QueryResult(QueryState state, List<Output> outputs, String errorCode, String message) {
        public static QueryResult running() {
            return new QueryResult(QueryState.RUNNING, List.of(), null, null);
        }

        public static QueryResult succeeded(List<Output> outputs) {
            return new QueryResult(QueryState.SUCCEEDED, outputs, null, null);
        }

        public static QueryResult failed(String errorCode, String message) {
            return new QueryResult(QueryState.FAILED, List.of(), errorCode, message);
        }

        public static QueryResult unknown(String message) {
            return new QueryResult(QueryState.UNKNOWN, List.of(), null, message);
        }
    }
}
