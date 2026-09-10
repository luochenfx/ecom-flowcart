package io.autocommerce.content.testsupport;

import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatResponse;
import io.autocommerce.core.step.ChatUsage;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.content.ai.LlmGateway;
import io.autocommerce.content.model.UsageRecorder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 手写 fake LLM 网关（项目约定：单测不引 mock 框架，用 hand-written fakes）。
 * 可预设响应序列、记录收到的请求、按需失败。
 */
public final class FakeLlmGateway implements LlmGateway {

    private final Deque<String> queued = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private ProviderException failure;
    private ProviderException nextFailure;
    private String model = "fake-model";

    /** 追加一个响应（按调用顺序取用；用尽后返回 "fake-response-N"）。 */
    public FakeLlmGateway respond(String content) {
        queued.add(content);
        return this;
    }

    /** 令接下来所有调用抛异常（模拟端点宕机/超时）。 */
    public FakeLlmGateway failWith(ProviderException exception) {
        this.failure = exception;
        return this;
    }

    /** 仅令下一次调用抛异常（模拟单步失败、其余步骤正常）。 */
    public FakeLlmGateway failNext(ProviderException exception) {
        this.nextFailure = exception;
        return this;
    }

    /** 恢复：清除已注入的失败（模拟端点恢复后重跑内容链）。 */
    public FakeLlmGateway recovers() {
        this.failure = null;
        this.nextFailure = null;
        return this;
    }

    public FakeLlmGateway withModel(String model) {
        this.model = model;
        return this;
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ChatResponse complete(ModelRequirement requirement, ChatRequest request, StepContext context) {
        requests.add(request);
        if (nextFailure != null) {
            ProviderException exception = nextFailure;
            nextFailure = null;
            throw exception;
        }
        if (failure != null) {
            throw failure;
        }
        String content = queued.isEmpty() ? "fake-response-" + requests.size() : queued.poll();
        ChatUsage usage = new ChatUsage(12, 34, 46);
        if (context instanceof UsageRecorder recorder) {
            recorder.recordUsage(model, usage);
        }
        return new ChatResponse(content, model, usage);
    }
}
