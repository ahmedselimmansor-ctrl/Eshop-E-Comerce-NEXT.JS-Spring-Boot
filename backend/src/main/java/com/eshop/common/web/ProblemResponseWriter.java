package com.eshop.common.web;

import com.eshop.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes an RFC 9457 problem document straight to the servlet response.
 *
 * <p>{@code @RestControllerAdvice} only sees exceptions raised while a controller is handling a
 * request. Rate limiting, authentication, and access denial all reject <em>before</em> that point,
 * from inside the filter chain, where a thrown exception escapes to the container and produces the
 * default {@code /error} body instead. Routing those rejections through here keeps a single error
 * shape across the whole API, so clients parse one thing.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ErrorCode code) throws IOException {
        write(request, response, code, Map.of());
    }

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ErrorCode code,
                      Map<String, Object> extraProperties) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", code.typeUri());
        problem.put("title", code.messageEn());
        problem.put("status", code.status().value());
        problem.put("detail", code.message(RequestContext.locale()));
        problem.put("instance", request.getRequestURI());
        problem.put("code", code.name());
        problem.put("requestId", RequestContext.requestId());
        problem.put("timestamp", Instant.now().toString());
        problem.putAll(extraProperties);

        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
