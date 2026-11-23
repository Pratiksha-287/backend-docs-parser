package com.gupta.liferayanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "liferay")
public class LiferayProperties {

    private String baseUrl;

    /**
     * Legacy HTTP Basic credentials. Only used when no OAuth 2 client id
     * is configured. Prefer {@link OAuth2}.
     */
    private String username;
    private String password;

    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxInMemorySize = 20 * 1024 * 1024;

    private Discovery discovery = new Discovery();
    private OAuth2 oauth2 = new OAuth2();

    public static class Discovery {

        private String catalogPath;

        public String getCatalogPath() {
            return catalogPath;
        }

        public void setCatalogPath(String catalogPath) {
            this.catalogPath = catalogPath;
        }
    }

    /**
     * OAuth 2.0 "client credentials" settings for a Liferay OAuth2
     * application (Control Panel > Security > OAuth 2 Administration).
     */
    public static class OAuth2 {

        private String clientId;
        private String clientSecret;

        /** Token endpoint path, relative to liferay.base-url. */
        private String tokenPath = "/o/oauth2/token";

        /** Optional space separated scopes. Blank = all scopes of the app. */
        private String scope;

        /**
         * How the client authenticates at the token endpoint:
         * "post" (client_id/client_secret in the form body) or
         * "basic" (HTTP Basic header).
         */
        private String clientAuthentication = "post";

        /** Refresh the token this long before it really expires. */
        private Duration expirySkew = Duration.ofSeconds(30);

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank();
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getTokenPath() {
            return tokenPath;
        }

        public void setTokenPath(String tokenPath) {
            this.tokenPath = tokenPath;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getClientAuthentication() {
            return clientAuthentication;
        }

        public void setClientAuthentication(String clientAuthentication) {
            this.clientAuthentication = clientAuthentication;
        }

        public Duration getExpirySkew() {
            return expirySkew;
        }

        public void setExpirySkew(Duration expirySkew) {
            this.expirySkew = expirySkew;
        }
    }

    public int getMaxInMemorySize() {
        return maxInMemorySize;
    }

    public void setMaxInMemorySize(int maxInMemorySize) {
        this.maxInMemorySize = maxInMemorySize;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    public void setDiscovery(Discovery discovery) {
        this.discovery = discovery;
    }

    public OAuth2 getOauth2() {
        return oauth2;
    }

    public void setOauth2(OAuth2 oauth2) {
        this.oauth2 = oauth2;
    }
}
