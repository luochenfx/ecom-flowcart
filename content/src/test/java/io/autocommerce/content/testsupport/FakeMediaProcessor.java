package io.autocommerce.content.testsupport;

import io.autocommerce.core.step.MediaProcessRequest;
import io.autocommerce.core.step.MediaProcessResult;
import io.autocommerce.core.step.MediaProcessor;
import io.autocommerce.core.step.StepExecutionException;

import java.util.ArrayList;
import java.util.List;

/** 手写 fake 媒体处理器：记录请求、返回确定的 storage_ref，可注入失败。 */
public final class FakeMediaProcessor implements MediaProcessor {

    private final List<MediaProcessRequest> requests = new ArrayList<>();
    private StepExecutionException failure;

    public FakeMediaProcessor failWith(StepExecutionException exception) {
        this.failure = exception;
        return this;
    }

    public List<MediaProcessRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public MediaProcessResult process(MediaProcessRequest request) throws StepExecutionException {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return new MediaProcessResult(request.mediaId(), "fake-store/" + request.mediaId() + ".jpg",
                request.variantLocale());
    }
}
