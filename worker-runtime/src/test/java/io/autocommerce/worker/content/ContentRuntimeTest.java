package io.autocommerce.worker.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时坐标推导（ADR-0003 确定性业务键在内容链的落点）。
 *
 * <p>为什么单独一条：workflowId 是"同一 Listing 只跑一次"的唯一依据（重复执行 = 重复烧 LLM 的钱），
 * 它的推导必须是纯函数且只有一处，不能依赖调用方各自拼接。
 */
class ContentRuntimeTest {

    @Test
    void workflowIdIsDeterministicFromListingId() {
        assertThat(ContentRuntime.workflowIdFor("listing-spu-1-shop-a"))
                .isEqualTo("content-listing-spu-1-shop-a");
        assertThat(ContentRuntime.workflowIdFor("listing-spu-1-shop-a"))
                .isEqualTo(ContentRuntime.workflowIdFor("listing-spu-1-shop-a"));
    }

    @Test
    void rejectsBlankListingId() {
        assertThatThrownBy(() -> ContentRuntime.workflowIdFor(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listingId");
    }
}
