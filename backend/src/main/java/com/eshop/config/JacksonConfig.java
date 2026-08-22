package com.eshop.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class JacksonConfig {

    /**
     * JSON conventions shared by every endpoint.
     *
     * <ul>
     *   <li>Instants serialise as ISO-8601 UTC strings, not epoch numbers - a numeric timestamp is
     *       ambiguous about precision and unreadable in logs.</li>
     *   <li>Nulls are omitted, so an absent optional field and an explicit {@code null} look the
     *       same on the wire and clients only branch on presence.</li>
     *   <li>Unknown properties are ignored on input, letting a newer client send fields an older
     *       backend has not deployed yet without a 400.</li>
     * </ul>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                .modules(new JavaTimeModule())
                .timeZone(TimeZone.getTimeZone("UTC"))
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                        SerializationFeature.FAIL_ON_EMPTY_BEANS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .featuresToEnable(
                        DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE,
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
