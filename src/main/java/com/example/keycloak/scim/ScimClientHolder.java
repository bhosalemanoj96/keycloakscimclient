package com.example.keycloak.scim;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.http.BasicAuth;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jboss.logging.Logger;

/**
 * Owns the single {@link ScimRequestBuilder} instance used to talk to the SCIM server.
 *
 * ScimRequestBuilder is AutoCloseable and internally recreates its underlying Apache HttpClient
 * lazily after close(), per the SDK's own docs, so it's safe to hold one long-lived instance here
 * for the whole extension's lifetime rather than opening/closing per call.
 */
public class ScimClientHolder implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ScimClientHolder.class);

    private final ScimRequestBuilder scimRequestBuilder;

    public ScimClientHolder(ScimProvisioningConfig config) {
        ScimClientConfig.ScimClientConfigBuilder configBuilder = ScimClientConfig.builder()
                .connectTimeout(config.connectTimeoutSeconds())
                .requestTimeout(config.requestTimeoutSeconds())
                .socketTimeout(config.requestTimeoutSeconds());

        if (config.skipTlsVerify()) {
            configBuilder.hostnameVerifier((hostname, session) -> true);
        }

        switch (config.authMode()) {
            case BASIC -> configBuilder.basicAuth(BasicAuth.builder()
                    .username(config.basicUsername())
                    .password(config.basicPassword())
                    .build());
            case BEARER -> {
                // NOTE: as of the SCIM-SDK wiki there is no first-class ".bearerAuth(...)" builder
                // method documented; the SDK instead lets you supply a preconfigured Apache
                // HttpClientBuilder. We use that to inject the Authorization header on every
                // request. If your installed scim-sdk-client version exposes a dedicated bearer
                // token method, prefer that instead and drop this interceptor.
                HttpRequestInterceptor authInterceptor = (request, context) ->
                        request.addHeader("Authorization", "Bearer " + config.bearerToken());
                configBuilder.httpClientBuilder(HttpClientBuilder.create().addInterceptorFirst(authInterceptor));
            }
            case NONE -> { /* no auth, e.g. local testing */ }
        }

        this.scimRequestBuilder = new ScimRequestBuilder(config.baseUrl(), configBuilder.build());
        LOG.infof("SCIM client initialized against %s (authMode=%s)", config.baseUrl(), config.authMode());
    }

    public ScimRequestBuilder getRequestBuilder() {
        return scimRequestBuilder;
    }

    @Override
    public void close() {
        try {
            scimRequestBuilder.close();
        } catch (Exception e) {
            LOG.warn("Error closing ScimRequestBuilder", e);
        }
    }
}
