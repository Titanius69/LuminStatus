package com.luminex_studios.luminstatus.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.luminex_studios.luminstatus.config.StatusConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional built-in web server for the status page.
 *
 * <p>Built on the JDK's own HTTP server rather than Netty or a framework: the
 * page is three cached strings and a health probe, and adding a shaded web
 * framework to a plugin jar for that would be a poor trade. Many hosts do not
 * allow a second open port, which is why this is off by default and the static
 * export is the recommended path.
 */
public final class StatusHttpServer {

    private static final String SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'";

    private final PagePublisher publisher;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public StatusHttpServer(PagePublisher publisher, Logger logger) {
        this.publisher = publisher;
        this.logger = logger;
    }

    public void start(StatusConfig.Http config) {
        stop();
        try {
            InetSocketAddress address = new InetSocketAddress(config.bindAddress(), config.port());
            server = HttpServer.create(address, 64);

            AtomicInteger counter = new AtomicInteger();
            executor = Executors.newFixedThreadPool(config.workerThreads(), runnable -> {
                Thread thread = new Thread(runnable, "LuminStatus HTTP #" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                switch (path) {
                    case "", "/", "/index.html" ->
                            respond(exchange, 200, "text/html; charset=utf-8", publisher.current().html());
                    case "/status.json" ->
                            respond(exchange, 200, "application/json; charset=utf-8", publisher.current().json());
                    case "/feed.atom" ->
                            respond(exchange, 200, "application/atom+xml; charset=utf-8", publisher.current().feed());
                    case "/healthz" ->
                            respond(exchange, 200, "text/plain; charset=utf-8", "ok");
                    default ->
                            respond(exchange, 404, "text/plain; charset=utf-8", "Not found");
                }
            });

            server.start();
            logger.info("Status page listening on http://{}:{}", config.bindAddress(), config.port());
        } catch (IOException ex) {
            logger.error("Could not bind the status page to {}:{}. "
                    + "The plugin keeps running; use the static export instead.",
                    config.bindAddress(), config.port(), ex);
            stop();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    private static void respond(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=15");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", SECURITY_POLICY);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, head ? -1 : payload.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }
}
