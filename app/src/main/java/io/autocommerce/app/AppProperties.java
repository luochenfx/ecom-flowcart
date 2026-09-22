package io.autocommerce.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code app.*} 装配配置（specs/0007 §7.2）。以 {@code @ConfigurationProperties} 绑定，
 * 由 {@link FlowcartApplication} 的 {@code @ConfigurationPropertiesScan} 注册。
 *
 * <p>键位：
 * <ul>
 *   <li>{@code app.role} —— 逗号串角色（{@link AppRole} 解析）；</li>
 *   <li>{@code app.media-root} —— 内容链媒体归档根（供 SPI provider 经 env 使用）；</li>
 *   <li>{@code app.data-root} —— 文档库根（v1 JSON 实现的 OrderStore / PublishStateStore）；</li>
 *   <li>{@code app.address-key} —— 地址明文加密密钥（AES-256，32 字节，装配 {@code AddressCipher}）；</li>
 *   <li>{@code app.temporal.target} / {@code app.temporal.namespace} —— 自托管 Temporal server 坐标；</li>
 *   <li>{@code app.flow.channels} —— channelId → chain 兜底映射（可选；v1 请求体携带 chain）。</li>
 * </ul>
 *
 * <p><b>当前消费口径（如实登记）</b>：{@code app.role} / {@code app.data-root} /
 * {@code app.address-key} / {@code app.temporal.*} 由本票的装配代码消费；
 * {@code app.media-root} 与 {@code app.flow.channels} 是 spec §7.2 声明的配置面，
 * v1 尚未由 app 代码读取（媒体根由内容 SPI provider 从其环境变量 {@code FLOWCART_MEDIA_ROOT} 取；
 * chain 由请求体声明）——在此保留为声明式配置，避免与 spec 键位脱节。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 运行时角色，逗号串（如 {@code api,worker,scheduler}）。 */
    private String role = "api,worker,scheduler";

    /** 内容链媒体归档根目录。 */
    private String mediaRoot;

    /** 文档库根目录（order / publish 的 JSON 文档库）。 */
    private String dataRoot;

    /** 地址明文加密密钥（AES-256，32 字节）。 */
    private String addressKey;

    private final Temporal temporal = new Temporal();

    private final Flow flow = new Flow();

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMediaRoot() {
        return mediaRoot;
    }

    public void setMediaRoot(String mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }

    public String getAddressKey() {
        return addressKey;
    }

    public void setAddressKey(String addressKey) {
        this.addressKey = addressKey;
    }

    public Temporal getTemporal() {
        return temporal;
    }

    public Flow getFlow() {
        return flow;
    }

    /** 自托管 Temporal server 坐标（ADR-0002）。 */
    public static class Temporal {

        private String target;

        private String namespace = "default";

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

    /** 编排链兜底配置。 */
    public static class Flow {

        /** channelId → chain（{@code domestic} / {@code cross_border}）兜底映射。 */
        private Map<String, String> channels = new LinkedHashMap<>();

        public Map<String, String> getChannels() {
            return channels;
        }

        public void setChannels(Map<String, String> channels) {
            this.channels = channels;
        }
    }
}
