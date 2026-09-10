package io.autocommerce.worker.content;

import io.autocommerce.catalog.store.JsonFileCatalogStore;
import io.autocommerce.content.ContentStepExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * activity 侧的前置条件（不依赖 Temporal 环境，直接调实现）。
 *
 * <p>内容链的前置 = 文档已落库（#19 采集或 Listing 装配写回）。缺文档是**装配/时序错误**而非业务
 * 降级，必须显式失败、不能被当成"内容为空"静默继续——那样会产出一份内容未就绪却被判就绪的 Listing。
 */
class ContentChainActivitiesImplTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDocument_isAssemblyStateError() {
        ContentChainActivitiesImpl activities = new ContentChainActivitiesImpl(
                new JsonFileCatalogStore(tempDir), new ContentStepExecutor(List.of(), Clock.systemUTC()));

        assertThatThrownBy(() -> activities.degradedSteps(new ListingRef("spu-x", "listing-x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无此 SPU 文档");
        assertThatThrownBy(() -> activities.runStep(
                new ContentStepInput(new ListingRef("spu-x", "listing-x"), "i18n.backfill", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无此 SPU 文档");
    }
}
