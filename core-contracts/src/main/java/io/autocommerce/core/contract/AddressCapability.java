package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.AddressRef;
import io.autocommerce.core.contract.dto.DecryptedAddress;

/**
 * 地址解密能力（specs/0005 §2/§8，消费方 = order workflow）。MASKED → DECRYPTED 延后取明文，
 * 触发点 = 下采购单前（1688 直发顾客需要）。
 */
public interface AddressCapability extends Capability {

    DecryptedAddress decryptAddress(AddressRef ref) throws AdapterException;
}
