package com.evolveum.polygon.connector.msgraphapi;

import com.evolveum.polygon.connector.msgraphapi.authentication.TokenProvider;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Test(groups = "unit")
public class GraphEndpointAuthenticationTest {

    public void retriesExactlyOnceAfterAuthentication401() throws Exception {
        try (ResponseSequenceServer server = new ResponseSequenceServer(401, 200)) {
            RecordingTokenProvider tokenProvider = new RecordingTokenProvider();
            GraphEndpoint endpoint = endpoint(tokenProvider);
            try (CloseableHttpResponse response = endpoint.executeRequest(
                    new HttpGet(server.url()), true)) {
                Assert.assertEquals(response.getStatusLine().getStatusCode(), 200);
            } finally {
                endpoint.close();
            }

            Assert.assertEquals(server.requestCount(), 2);
            Assert.assertEquals(tokenProvider.acquireCount, 1);
            Assert.assertEquals(tokenProvider.reacquireCount, 1);
            Assert.assertEquals(server.authorizationHeaders,
                    List.of("Bearer cached-test-token", "Bearer refreshed-test-token"));
        }
    }

    public void doesNotLoopAfterSecond401() throws Exception {
        try (ResponseSequenceServer server = new ResponseSequenceServer(401, 401, 200)) {
            RecordingTokenProvider tokenProvider = new RecordingTokenProvider();
            GraphEndpoint endpoint = endpoint(tokenProvider);
            try {
                endpoint.executeRequest(new HttpGet(server.url()), true);
                Assert.fail("A repeated 401 was accepted");
            } catch (ConnectionFailedException e) {
                Assert.assertFalse(e.toString().contains("cached-test-token"));
                Assert.assertFalse(e.toString().contains("refreshed-test-token"));
            } finally {
                endpoint.close();
            }

            Assert.assertEquals(server.requestCount(), 2);
            Assert.assertEquals(tokenProvider.acquireCount, 1);
            Assert.assertEquals(tokenProvider.reacquireCount, 1);
        }
    }

    public void reusesSameTokenProviderAcrossRequests() throws Exception {
        try (ResponseSequenceServer server = new ResponseSequenceServer(200, 200)) {
            RecordingTokenProvider tokenProvider = new RecordingTokenProvider();
            GraphEndpoint endpoint = endpoint(tokenProvider);
            try {
                try (CloseableHttpResponse ignored = endpoint.executeRequest(new HttpGet(server.url()), true)) {
                    // Response is closed by the test.
                }
                try (CloseableHttpResponse ignored = endpoint.executeRequest(new HttpGet(server.url()), true)) {
                    // Response is closed by the test.
                }
            } finally {
                endpoint.close();
            }

            Assert.assertEquals(server.requestCount(), 2);
            Assert.assertEquals(tokenProvider.acquireCount, 2);
            Assert.assertEquals(tokenProvider.reacquireCount, 0);
            Assert.assertEquals(tokenProvider.closeCount, 1);
        }
    }

    private static GraphEndpoint endpoint(TokenProvider tokenProvider) {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setDiscoverSchema(false);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        return new GraphEndpoint(configuration, tokenProvider, httpClient);
    }

    private static final class RecordingTokenProvider implements TokenProvider {
        private int acquireCount;
        private int reacquireCount;
        private int closeCount;

        @Override
        public String acquireToken(String scope) {
            acquireCount++;
            return "cached-test-token";
        }

        @Override
        public String reacquireToken(String scope) {
            reacquireCount++;
            return "refreshed-test-token";
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class ResponseSequenceServer implements AutoCloseable {
        private final HttpServer server;
        private final int[] statuses;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> authorizationHeaders = new ArrayList<>();

        private ResponseSequenceServer(int... statuses) throws Exception {
            this.statuses = statuses;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/graph", exchange -> {
                int requestNumber = requests.getAndIncrement();
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                int status = statuses[Math.min(requestNumber, statuses.length - 1)];
                byte[] body = status == 401
                        ? "{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}".getBytes()
                        : new byte[0];
                exchange.sendResponseHeaders(status, body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/graph";
        }

        private int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
