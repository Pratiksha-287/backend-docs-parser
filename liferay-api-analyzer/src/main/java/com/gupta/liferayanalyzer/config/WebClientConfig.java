package com.gupta.liferayanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(LiferayProperties.class)
public class WebClientConfig {

    /**
     * Plain WebClient pointing at Liferay. No Authorization header is set
     * here when OAuth 2 is configured: LiferayTokenService obtains a bearer
     * token and LiferayConnectionService adds it per request.
     *
     * Legacy fallback: if no OAuth 2 client id is configured but a
     * username is, HTTP Basic is used as before.
     */
    @Bean
    public WebClient liferayWebClient(
            LiferayProperties properties) {

        WebClient.Builder clientBuilder = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .codecs(configurer ->
                        configurer.defaultCodecs()
                                .maxInMemorySize(properties.getMaxInMemorySize())
                );

        boolean useLegacyBasicAuth =
                !properties.getOauth2().isConfigured()
                        && properties.getUsername() != null
                        && !properties.getUsername().isBlank();

        if (useLegacyBasicAuth) {

            clientBuilder.defaultHeaders(headers ->
                    headers.setBasicAuth(
                            properties.getUsername(),
                            properties.getPassword() == null
                                    ? ""
                                    : properties.getPassword()
                    )
            );
        }

        return clientBuilder.build();
    }
}
