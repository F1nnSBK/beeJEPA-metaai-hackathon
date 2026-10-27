package com.beejepa.telemetry;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.Locale;

@Filter("/**")
public class TelemetryHttpFilter implements HttpServerFilter {

    private final TelemetryService telemetryService;

    public TelemetryHttpFilter(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.after();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        long startTime = System.nanoTime();
        String uri = request.getPath();
        String method = request.getMethodName();
        String clientIp = "127.0.0.1";

        try {
            if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
                clientIp = request.getRemoteAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }

        final String finalClientIp = clientIp;

        return Flux.from(chain.proceed(request))
                .doOnNext(response -> {
                    long durationNanos = System.nanoTime() - startTime;
                    double durationMs = durationNanos / 1_000_000.0;
                    int status = response.getStatus().getCode();

                    // Standard W3C Server-Timing header for browser devtools & latency profiling
                    response.header("Server-Timing", String.format(Locale.US, "total;dur=%.2f", durationMs));

                    String platform = determinePlatform(uri);
                    telemetryService.recordRequest(platform, method, uri, status, durationMs, finalClientIp);
                });
    }

    private String determinePlatform(String uri) {
        if (uri.startsWith("/api/v1/muse")) {
            return "META_MUSE";
        } else if (uri.startsWith("/api/v1/events")) {
            return "V_JEPA";
        } else if (uri.startsWith("/api/v1/telemetry")) {
            return "TELEMETRY";
        } else if (uri.startsWith("/prometheus")) {
            return "PROMETHEUS";
        } else if (uri.startsWith("/assets") || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".ico") || uri.endsWith(".html")) {
            return "WEB_STATIC";
        }
        return "JAVA_HTTP";
    }
}
