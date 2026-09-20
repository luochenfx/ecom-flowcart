package io.autocommerce.publish;

import io.autocommerce.core.contract.AdapterException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter 错误三类 → publish 编排归类的口径（AC-4 / specs/0005 §6）。
 */
class PublishFailureTest {

    @Test
    void adapterKinds_mapToOrchestrationKinds() {
        assertThat(PublishFailure.classify(AdapterException.retryable("429", "限流")).kind())
                .isEqualTo(PublishFailureKind.RETRYABLE);
        assertThat(PublishFailure.classify(AdapterException.nonRetryable("FX_CAT", "类目违规")).kind())
                .isEqualTo(PublishFailureKind.REJECTED);
        assertThat(PublishFailure.classify(AdapterException.ambiguous("FX_TIMEOUT", "超时")).kind())
                .isEqualTo(PublishFailureKind.AMBIGUOUS);
    }

    @Test
    void reason_isPlatformCodePlusMessage() {
        PublishFailure failure = PublishFailure.classify(AdapterException.nonRetryable("FX_CAT", "类目违规"));
        assertThat(failure.reason()).isEqualTo("FX_CAT: 类目违规");
    }

    @Test
    void reasonWithoutPlatformCode_isJustMessage() {
        PublishFailure failure = PublishFailure.classify(AdapterException.ambiguous(null, "超时"));
        assertThat(failure.reason()).isEqualTo("超时");
    }

    @Test
    void nonAdapterException_isUnexpectedBug() {
        PublishFailure failure = PublishFailure.classify(new IllegalStateException("boom"));
        assertThat(failure.kind()).isEqualTo(PublishFailureKind.UNEXPECTED);
        assertThat(failure.reason()).contains("IllegalStateException").contains("boom");
    }

    @Test
    void nullThrowableRejected() {
        assertThatThrownBy(() -> PublishFailure.classify(null)).isInstanceOf(NullPointerException.class);
    }
}
