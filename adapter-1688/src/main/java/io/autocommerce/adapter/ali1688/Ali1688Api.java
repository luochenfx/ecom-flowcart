package io.autocommerce.adapter.ali1688;

/**
 * 1688 开放平台 param2 端点（#23 按官方 apidoc 校准）。
 *
 * <p>{@code namespace} + {@code name} 直接决定两件事：请求 URL
 * （{@code /openapi/param2/1/{namespace}/{name}/{appKey}}）与签名因子一
 * （{@link Ali1688Signature#urlPath(String, String, String)}，同一串去掉前导 {@code /openapi}）。
 * 官方 apidoc 的「接口 id」形如 {@code com.alibaba.trade:alibaba.trade.cancel-1}，即
 * {@code namespace:name-版本}——本枚举只记前两段。
 *
 * @param namespace 官方命名空间（接口 id 冒号前段）
 * @param name      官方接口名（接口 id 冒号后段，去 {@code -1} 版本后缀）
 * @param write     是否写操作：<b>写操作超时 / 中断 = AMBIGUOUS</b>（不知是否生效，不重发、走 reconcile），
 *                  读操作超时 = RETRYABLE（重试安全）
 * @param siteAware 官方 apidoc 是否把 {@code webSite=1688} 标为必填（cancel / 物流买家视角必填）
 */
enum Ali1688Api {

    /** 快速创建采购订单（官方：一步创建，无需先调下单预览）。flow：general 批发 / saleproxy 一件代发。 */
    TRADE_FAST_CREATE_ORDER("com.alibaba.trade", "alibaba.trade.fastCreateOrder", true, false),

    /** 取消交易：仅未付款可撤，已付款须走售后退款。 */
    TRADE_CANCEL("com.alibaba.trade", "alibaba.trade.cancel", true, true),

    /** 免密代扣（自动扣款）；未开通免密时平台返回签约 / 收银台链接。 */
    TRADE_PROTOCOL_PAY_PREPARE("com.alibaba.trade", "alibaba.trade.pay.protocolPay.preparePay", true,
            false),

    /** 获取交易订单的物流跟踪信息（买家视角）。需向开放平台申请权限。 */
    LOGISTICS_TRACE_BUYER_VIEW("com.alibaba.logistics",
            "alibaba.trade.getLogisticsTraceInfo.buyerView", false, true);

    private final String namespace;
    private final String name;
    private final boolean write;
    private final boolean siteAware;

    Ali1688Api(String namespace, String name, boolean write, boolean siteAware) {
        this.namespace = namespace;
        this.name = name;
        this.write = write;
        this.siteAware = siteAware;
    }

    String namespace() {
        return namespace;
    }

    /** 官方接口名（不覆写 {@link Enum#name()}，故不叫 name()）。 */
    String apiName() {
        return name;
    }

    boolean write() {
        return write;
    }

    boolean siteAware() {
        return siteAware;
    }

    /** 请求路径（签名因子一同串，前加 {@code /openapi}）。 */
    String path(String appKey) {
        return "/openapi/param2/1/" + namespace + "/" + name + "/" + appKey;
    }
}
