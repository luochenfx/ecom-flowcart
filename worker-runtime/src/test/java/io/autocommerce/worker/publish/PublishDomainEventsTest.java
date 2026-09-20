package io.autocommerce.worker.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.publish.testsupport.PublishFixtures;
import io.autocommerce.worker.event.DomainEvents;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * listing 事件 envelope 纪律（AC-5/AC-6）：{@code envelope.id} 由事实键确定性派生——同一业务事实重放
 * 产出<b>同一</b> id，不同事实产出不同 id；且 payload 过 {@code message.schema.json} 契约门。
 */
class PublishDomainEventsTest {

    private static final ObjectMapper MAPPER = ContractObjectMapper.create();

    @Test
    void listingPublished_sameFactReplay_sameId_andSchemaValid() {
        Envelope first = DomainEvents.listingPublished("PublishWorkflow", PublishFixtures.LISTING_ID,
                "fx-item-1", PublishFixtures.PUBLISHED_AT);
        Envelope replay = DomainEvents.listingPublished("PublishWorkflow", PublishFixtures.LISTING_ID,
                "fx-item-1", PublishFixtures.PUBLISHED_AT);

        assertThat(replay.id()).as("listing.published 同一事实重放 → 同一幂等锚").isEqualTo(first.id());
        assertThat(first.type()).isEqualTo(EventTypes.LISTING_PUBLISHED);
        assertThat(first.entityRef().type()).isEqualTo("listing");
        assertThat(first.entityRef().id()).isEqualTo(PublishFixtures.LISTING_ID);
        assertThat(first.occurredAt()).isEqualTo(PublishFixtures.PUBLISHED_AT);
        assertValid(first);
    }

    @Test
    void listingAmbiguous_sameFactReplay_sameId_andSchemaValid() {
        Envelope first = DomainEvents.listingAmbiguous("PublishWorkflow", PublishFixtures.LISTING_ID,
                "FX_TIMEOUT: 超时", PublishFixtures.PUBLISHED_AT);
        Envelope replay = DomainEvents.listingAmbiguous("PublishWorkflow", PublishFixtures.LISTING_ID,
                "FX_TIMEOUT: 超时", PublishFixtures.PUBLISHED_AT);

        assertThat(replay.id()).as("listing.ambiguous 同一事实重放 → 同一幂等锚").isEqualTo(first.id());
        assertThat(first.type()).isEqualTo(EventTypes.LISTING_AMBIGUOUS);
        assertValid(first);
    }

    @Test
    void differentFacts_differentIds() {
        Envelope published = DomainEvents.listingPublished("PublishWorkflow", PublishFixtures.LISTING_ID,
                "fx-item-1", PublishFixtures.PUBLISHED_AT);
        Envelope ambiguous = DomainEvents.listingAmbiguous("PublishWorkflow", PublishFixtures.LISTING_ID,
                "x", PublishFixtures.PUBLISHED_AT);

        assertThat(published.id()).as("不同 type → 不同 id").isNotEqualTo(ambiguous.id());
        assertThat(published.id())
                .as("确定性派生仍须保持 message.schema.json 的 Envelope.id format: uuid")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    private static void assertValid(Envelope envelope) {
        ContractAssertions.assertValid(ContractSchemas.payloadFor(envelope.type()), envelope.payload(),
                "事件 payload: " + envelope.type());

        ObjectNode document = MAPPER.createObjectNode();
        document.put("schema_version", "0.1.0");
        document.set("envelope", MAPPER.valueToTree(envelope));
        ContractAssertions.assertValid(ContractSchemas.message(), document,
                "listing 事件 envelope 过 message.schema.json 契约门");
    }
}
