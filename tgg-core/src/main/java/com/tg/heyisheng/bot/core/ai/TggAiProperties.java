package com.tg.heyisheng.bot.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 审核层配置（模块九 · L2/L3/L4）。
 *
 * <p><b>全部默认关闭</b>：L3 会把消息正文**送往第三方**（DeepSeek），L2/L4 依赖部署方自备的本地模型服务。
 * 默认开启等于默认把用户消息发出去——因此必须显式启用（本项目 failover / admission 同此纪律）。
 *
 * <p>⚠️ 部署时必须显式设置 {@code tgg.ai.deepseek.enabled=true} 才会生效，
 * 且**不设不会报错**（只是该层不参与审核）——这类静默缺失只能靠部署文档提醒。
 */
@ConfigurationProperties(prefix = "tgg.ai")
public class TggAiProperties {

    private final DeepSeek deepseek = new DeepSeek();
    private final Local local = new Local();

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public Local getLocal() {
        return local;
    }

    /** L3 · DeepSeek 云端。 */
    public static class DeepSeek {
        /** 是否启用。默认 **false**（启用后消息正文会出站）。 */
        private boolean enabled = false;

        /**
         * API key。**必须**从环境变量 {@code TGG_DEEPSEEK_API_KEY} 注入，
         * 绝不硬编码、绝不进仓库。
         */
        private String apiKey;

        /** 接口基址。 */
        private String baseUrl = "https://api.deepseek.com";

        /** 模型名。 */
        private String model = "deepseek-chat";

        /** 单次调用超时（毫秒）。审核在消息处理主链路上，超时必须短。 */
        private int timeoutMs = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** L2 / L4 · 本地模型（默认关闭；只提供接入位，具体模型由部署方提供）。 */
    public static class Local {
        /** L2（ML 分类器）是否启用。默认 false。 */
        private boolean l2Enabled = false;
        /** L2 推理端点。 */
        private String l2Endpoint;
        /** L4（零样本）是否启用。默认 false。 */
        private boolean l4Enabled = false;
        /** L4 推理端点。 */
        private String l4Endpoint;
        /** 本地调用超时（毫秒）。 */
        private int timeoutMs = 2000;

        public boolean isL2Enabled() {
            return l2Enabled;
        }

        public void setL2Enabled(boolean l2Enabled) {
            this.l2Enabled = l2Enabled;
        }

        public String getL2Endpoint() {
            return l2Endpoint;
        }

        public void setL2Endpoint(String l2Endpoint) {
            this.l2Endpoint = l2Endpoint;
        }

        public boolean isL4Enabled() {
            return l4Enabled;
        }

        public void setL4Enabled(boolean l4Enabled) {
            this.l4Enabled = l4Enabled;
        }

        public String getL4Endpoint() {
            return l4Endpoint;
        }

        public void setL4Endpoint(String l4Endpoint) {
            this.l4Endpoint = l4Endpoint;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
