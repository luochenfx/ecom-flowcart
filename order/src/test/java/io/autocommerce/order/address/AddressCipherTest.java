package io.autocommerce.order.address;

import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AddressCipher}：AES-256-GCM 往返 + 密钥长度校验 + 密文不可回显明文。 */
class AddressCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptDecryptRoundTrips() {
        AddressCipher cipher = new AddressCipher(KEY);
        DecryptedAddress address = OrderFixtures.decryptedAddress();

        String payload = cipher.encrypt(address);

        assertThat(payload).as("密文不得回显明文").doesNotContain(address.receiverPhone());
        assertThat(cipher.decrypt(payload)).isEqualTo(address);
    }

    @Test
    void randomIvProducesDifferentCiphertext() {
        AddressCipher cipher = new AddressCipher(KEY);
        assertThat(cipher.encrypt(OrderFixtures.decryptedAddress()))
                .isNotEqualTo(cipher.encrypt(OrderFixtures.decryptedAddress()));
    }

    @Test
    void rejectsNonAes256Key() {
        assertThatThrownBy(() -> new AddressCipher(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void tamperedCiphertextFails() {
        AddressCipher cipher = new AddressCipher(KEY);
        String payload = cipher.encrypt(OrderFixtures.decryptedAddress());
        String tampered = (payload.charAt(0) == 'A' ? "B" : "A") + payload.substring(1);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
