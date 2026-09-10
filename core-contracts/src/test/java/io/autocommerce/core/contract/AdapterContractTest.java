package io.autocommerce.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.order.model.SupplierRef;
import io.autocommerce.core.testutil.ContractObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapter 契约面补齐的机器门（#39，specs/0005 §2 / §5）。两处缺口都是「签名 / 字段与规范不一致」，
 * 单看实现代码看不出失守，故在此用契约测试锁住：
 * <ol>
 *   <li><b>采购草稿行</b>：1688 {@code alibaba.trade.fastCreateOrder} 的 {@code cargoParamList[]}
 *       每项必需 {@code {offerId, specId, quantity}}——三键必须由标准模型即可构造（缺 {@code specId}
 *       是缺必填项，请求体根本构造不出来）；</li>
 *   <li><b>凭据刷新</b>：{@code AuthCapability.refresh} 入 / 出参 = {@link CredentialView}（解密内存
 *       对象）；回退成密文 {@link Credential} 就要求 Adapter 持有 AES 密钥，§5「Adapter 接口收到的是
 *       解密后的内存对象」即失守。</li>
 * </ol>
 */
class AdapterContractTest {

    private final ObjectMapper mapper = ContractObjectMapper.create();

    @Test
    void purchaseDraftItem_constructsCargoParamListEntry() throws Exception {
        PurchaseDraft draft = new PurchaseDraft(
                new SupplierRef("1688", "supplier-2200", "深圳市 XX 电子厂"),
                List.of(new PurchaseDraft.PurchaseDraftItem(
                        "523681097354", "6688990011", "b266e0726506185beaf205cbae88530d", 5,
                        new Money("45.90", "CNY"))),
                new DecryptedAddress("张三", "13800000000", "中国", "浙江省", "杭州市", "西湖区",
                        "文三路 1 号", "310012"));

        JsonNode item = mapper.valueToTree(draft).get("items").get(0);

        // cargoParamList[] 每项 = {offerId, specId, quantity}：行级三键齐备
        assertThat(item.get("source_offer_id").asText()).isEqualTo("6688990011");
        assertThat(item.get("source_spec_id").asText()).isEqualTo("b266e0726506185beaf205cbae88530d");
        assertThat(item.get("quantity").asInt()).isEqualTo(5);
        // skuId（规格组合内部 id）与 specId（下单键）是两个字段，前者不顶替后者
        assertThat(item.has("source_sku_id")).isTrue();

        // 双向：序列化形态可原样反序列化回模型（无字段丢失）
        assertThat(mapper.treeToValue(mapper.valueToTree(draft), PurchaseDraft.class)).isEqualTo(draft);
    }

    @Test
    void authRefresh_takesAndReturnsCredentialView() throws Exception {
        // 签名回退到密文 Credential 即 NoSuchMethodException → 测试失败（§5 的机器门）
        Method refresh = AuthCapability.class.getMethod("refresh", CredentialView.class);

        assertThat(refresh.getReturnType()).isEqualTo(CredentialView.class);
        assertThat(refresh.getExceptionTypes()).containsExactly(AdapterException.class);
        // 直接改签名（不加加法重载）：refresh 只此一个签名，不留注定无人实现的死方法
        assertThat(AuthCapability.class.getMethods())
                .filteredOn(m -> "refresh".equals(m.getName()))
                .hasSize(1);
    }
}
