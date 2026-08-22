package com.eshop.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Elasticsearch client for the product search index.
 *
 * <p>Guarded by {@code eshop.elasticsearch.enabled} so the application still boots — with search
 * falling back to PostgreSQL trigram matching — when the cluster is unavailable or intentionally
 * not deployed (small dev environments, CI).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eshop.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchConfig {

    private final AppProperties properties;

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient() {
        AppProperties.Elasticsearch config = properties.getElasticsearch();

        List<HttpHost> hosts = Arrays.stream(config.getUris().split(","))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .map(URI::create)
                .map(uri -> new HttpHost(uri.getHost(), resolvePort(uri), uri.getScheme()))
                .toList();

        log.info("elasticsearch_client_init hosts={} index={}", hosts, config.getProductIndex());

        RestClientBuilderHolder holder = new RestClientBuilderHolder(config);
        return RestClient.builder(hosts.toArray(HttpHost[]::new))
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(config.getConnectTimeoutMs())
                        .setSocketTimeout(config.getSocketTimeoutMs()))
                .setHttpClientConfigCallback(holder::configure)
                .build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(mapper)));
    }

    private static int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 9200;
    }

    /** Small holder so the credentials callback stays readable. */
    private record RestClientBuilderHolder(AppProperties.Elasticsearch config) {

        org.apache.http.impl.nio.client.HttpAsyncClientBuilder configure(
                org.apache.http.impl.nio.client.HttpAsyncClientBuilder builder) {

            builder.setMaxConnTotal(64).setMaxConnPerRoute(32);

            if (!config.getUsername().isBlank()) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
                builder.setDefaultCredentialsProvider(credentials);
            }
            return builder;
        }
    }
}
