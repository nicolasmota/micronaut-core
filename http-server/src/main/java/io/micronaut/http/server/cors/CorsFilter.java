/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.http.server.cors;

import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_MAX_AGE;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static io.micronaut.http.HttpHeaders.ORIGIN;
import static io.micronaut.http.HttpHeaders.VARY;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.server.HttpServerConfiguration;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsible for handling CORS requests and responses.
 *
 * @author James Kleeh
 * @author Graeme Rocher
 * @since 1.0
 */
@Filter("/**")
public class CorsFilter implements HttpServerFilter {

    protected final HttpServerConfiguration.CorsConfiguration corsConfiguration;

    /**
     * @param corsConfiguration The {@link CorsOriginConfiguration} instance
     */
    public CorsFilter(HttpServerConfiguration.CorsConfiguration corsConfiguration) {
        this.corsConfiguration = corsConfiguration;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        boolean originHeaderPresent = request.getHeaders().getOrigin().isPresent();
        if (originHeaderPresent) {
            Optional<MutableHttpResponse<?>> response = handleRequest(request);
            if (response.isPresent()) {
                return Publishers.just(response.get());
            } else {
                return Publishers.map(chain.proceed(request), mutableHttpResponse -> {
                    handleResponse(request, mutableHttpResponse);
                    return mutableHttpResponse;
                });
            }
        } else {
            return chain.proceed(request);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Handles a CORS response.
     *
     * @param request  The {@link HttpRequest} object
     * @param response The {@link MutableHttpResponse} object
     */
    protected void handleResponse(HttpRequest<?> request, MutableHttpResponse<?> response) {
        HttpHeaders headers = request.getHeaders();
        Optional<String> originHeader = headers.getOrigin();
        originHeader.ifPresent(requestOrigin -> {

            Optional<CorsOriginConfiguration> optionalConfig = getConfiguration(requestOrigin);

            if (optionalConfig.isPresent()) {
                CorsOriginConfiguration config = optionalConfig.get();

                if (CorsUtil.isPreflightRequest(request)) {
                    Optional<HttpMethod> result = headers.getFirst(ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.class);
                    setAllowMethods(result.get(), response);
                    Argument<List> type = Argument.of(List.class, String.class);
                    Optional<List> allowedHeaders = headers.get(ACCESS_CONTROL_REQUEST_HEADERS, type);
                    allowedHeaders.ifPresent(val ->
                        setAllowHeaders(val, response)
                    );

                    setMaxAge(config.getMaxAge(), response);
                }

                setOrigin(requestOrigin, response);
                setVary(response);
                setExposeHeaders(config.getExposedHeaders(), response);
                setAllowCredentials(config, response);
            }
        });
    }

    /**
     * Handles a CORS {@link HttpRequest}.
     *
     * @param request The {@link HttpRequest} object
     * @return An optional {@link MutableHttpResponse}. The request should proceed normally if empty
     */
    protected Optional<MutableHttpResponse<?>> handleRequest(HttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        Optional<String> originHeader = headers.getOrigin();
        if (originHeader.isPresent()) {

            String requestOrigin = originHeader.get();
            boolean preflight = CorsUtil.isPreflightRequest(request);

            Optional<CorsOriginConfiguration> optionalConfig = getConfiguration(requestOrigin);

            if (optionalConfig.isPresent()) {
                CorsOriginConfiguration config = optionalConfig.get();

                HttpMethod requestMethod = request.getMethod();

                List<HttpMethod> allowedMethods = config.getAllowedMethods();

                if (!isAnyMethod(allowedMethods)) {
                    HttpMethod methodToMatch = preflight ? headers.getFirst(ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.class).orElse(requestMethod) : requestMethod;
                    if (allowedMethods.stream().noneMatch(method -> method.equals(methodToMatch))) {
                        return Optional.of(HttpResponse.status(HttpStatus.FORBIDDEN));
                    }
                }

                if (preflight) {
                    Optional<List> accessControlHeaders = headers.get(ACCESS_CONTROL_REQUEST_HEADERS, Argument.of(List.class, String.class));

                    List<String> allowedHeaders = config.getAllowedHeaders();

                    if (!isAny(allowedHeaders) && accessControlHeaders.isPresent()) {
                        if (!accessControlHeaders.get().stream()
                            .allMatch(header -> allowedHeaders.stream()
                                .anyMatch(allowedHeader -> allowedHeader.equals(header.toString().trim())))) {
                            return Optional.of(HttpResponse.status(HttpStatus.FORBIDDEN));
                        }
                    }

                    MutableHttpResponse<Object> ok = HttpResponse.ok();
                    handleResponse(request, ok);
                    return Optional.of(ok);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * @param config   The {@link CorsOriginConfiguration} instance
     * @param response The {@link MutableHttpResponse} object
     */
    protected void setAllowCredentials(CorsOriginConfiguration config, MutableHttpResponse<?> response) {
        if (config.isAllowCredentials()) {
            response.header(ACCESS_CONTROL_ALLOW_CREDENTIALS, Boolean.toString(true));
        }
    }

    /**
     * @param exposedHeaders A list of the exposed headers
     * @param response       The {@link MutableHttpResponse} object
     */
    protected void setExposeHeaders(List<String> exposedHeaders, MutableHttpResponse<?> response) {
        exposedHeaders.forEach(header -> response.header(ACCESS_CONTROL_EXPOSE_HEADERS, header));
    }

    /**
     * @param response The {@link MutableHttpResponse} object
     */
    protected void setVary(MutableHttpResponse<?> response) {
        response.header(VARY, ORIGIN);
    }


    /**
     * @param origin   The origin
     * @param response The {@link MutableHttpResponse} object
     */
    protected void setOrigin(String origin, MutableHttpResponse response) {
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }

    /**
     * @param method   The {@link HttpMethod} object
     * @param response The {@link MutableHttpResponse} object
     */
    protected void setAllowMethods(HttpMethod method, MutableHttpResponse response) {
        response.header(ACCESS_CONTROL_ALLOW_METHODS, method);
    }

    /**
     * @param optionalAllowHeaders A list with optional allow headers
     * @param response             The {@link MutableHttpResponse} object
     */
    protected void setAllowHeaders(List optionalAllowHeaders, MutableHttpResponse response) {
        optionalAllowHeaders.forEach(header ->
            response.header(ACCESS_CONTROL_ALLOW_HEADERS, header.toString())
        );
    }

    /**
     * @param maxAge   The max age
     * @param response The {@link MutableHttpResponse} object
     */
    protected void setMaxAge(long maxAge, MutableHttpResponse response) {
        if (maxAge > -1) {
            response.header(ACCESS_CONTROL_MAX_AGE, Long.toString(maxAge));
        }
    }

    private Optional<CorsOriginConfiguration> getConfiguration(String requestOrigin) {
        Map<String, CorsOriginConfiguration> corsConfigurations = corsConfiguration.getConfigurations();
        for (Map.Entry<String, CorsOriginConfiguration> config : corsConfigurations.entrySet()) {
            List<String> allowedOrigins = config.getValue().getAllowedOrigins();
            if (!allowedOrigins.isEmpty()) {
                boolean matches = false;
                if (isAny(allowedOrigins)) {
                    matches = true;
                }
                if (!matches) {
                    matches = allowedOrigins.stream().anyMatch(origin -> {
                        if (origin.equals(requestOrigin)) {
                            return true;
                        }
                        Pattern p = Pattern.compile(origin);
                        Matcher m = p.matcher(requestOrigin);
                        return m.matches();
                    });
                }

                if (matches) {
                    return Optional.of(config.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private boolean isAny(List<String> values) {
        return values == CorsOriginConfiguration.ANY;
    }

    private boolean isAnyMethod(List<HttpMethod> allowedMethods) {
        return allowedMethods == CorsOriginConfiguration.ANY_METHOD;
    }
}
