package com.evolveum.polygon.connector.msgraphapi.authentication;

import com.evolveum.polygon.connector.msgraphapi.MSGraphConfiguration;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.MsalException;
import com.microsoft.aad.msal4j.MsalServiceException;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.evolveum.polygon.connector.msgraphapi.authentication.TokenAcquisitionException.Reason;

/**
 * MSAL4J confidential-client token provider. One instance, and therefore one
 * MSAL application and token cache, is retained for the owning connector
 * configuration.
 */
public final class MsalTokenProvider implements TokenProvider {

    static final String AUTHORITY_ROOT = "https://login.microsoftonline.com/";
    private static final long MAX_CREDENTIAL_FILE_BYTES = 1024L * 1024L;

    private final TokenClient client;
    private final ExecutorService executorService;
    private final boolean proxyConfigured;

    public MsalTokenProvider(MSGraphConfiguration configuration, SSLSocketFactory sslSocketFactory)
            throws TokenAcquisitionException {
        this(configuration, sslSocketFactory, new DefaultCredentialFactory(), new DefaultApplicationFactory());
    }

    MsalTokenProvider(
            MSGraphConfiguration configuration,
            SSLSocketFactory sslSocketFactory,
            CredentialFactory credentialFactory,
            ApplicationFactory applicationFactory) throws TokenAcquisitionException {
        if (configuration == null) {
            throw new TokenAcquisitionException(Reason.UNEXPECTED,
                    "Authentication configuration is not available.");
        }
        if (StringUtil.isBlank(configuration.getClientId())) {
            throw new TokenAcquisitionException(Reason.INVALID_CLIENT,
                    "The Microsoft Entra client ID is not valid.");
        }
        if (StringUtil.isBlank(configuration.getTenantId())) {
            throw new TokenAcquisitionException(Reason.INVALID_TENANT,
                    "The Microsoft Entra tenant ID is not valid.");
        }

        this.proxyConfigured = configuration.hasProxy();
        this.executorService = Executors.newSingleThreadExecutor(new AuthenticationThreadFactory());

        try {
            IClientCredential credential = configuration.isCertificateBasedAuthentication()
                    ? credentialFactory.fromCertificate(
                            configuration.getCertificatePath(), configuration.getPrivateKeyPath())
                    : credentialFactory.fromSecret(configuration.getClientSecret());

            Proxy proxy = proxyConfigured
                    ? new Proxy(Proxy.Type.HTTP, configuration.getProxyAddress())
                    : null;
            String authority = AUTHORITY_ROOT + configuration.getTenantId();
            this.client = applicationFactory.create(
                    configuration.getClientId(), credential, authority, proxy, sslSocketFactory, executorService);
        } catch (TokenAcquisitionException e) {
            executorService.shutdownNow();
            throw e;
        } catch (MalformedURLException | IllegalArgumentException e) {
            executorService.shutdownNow();
            throw new TokenAcquisitionException(Reason.INVALID_TENANT,
                    "The Microsoft Entra authority is not valid.");
        } catch (Exception e) {
            executorService.shutdownNow();
            throw classify(e, proxyConfigured);
        }
    }

    @Override
    public String acquireToken(String scope) throws TokenAcquisitionException {
        return acquire(scope, false);
    }

    @Override
    public String reacquireToken(String scope) throws TokenAcquisitionException {
        return acquire(scope, true);
    }

    private String acquire(String scope, boolean skipCache) throws TokenAcquisitionException {
        if (StringUtil.isBlank(scope)) {
            throw new TokenAcquisitionException(Reason.UNEXPECTED,
                    "An authentication scope is required.");
        }

        ClientCredentialParameters parameters = ClientCredentialParameters
                .builder(Collections.singleton(scope))
                .skipCache(skipCache)
                .build();
        try {
            IAuthenticationResult result = client.acquireToken(parameters).get();
            if (result == null || StringUtil.isBlank(result.accessToken())) {
                throw new TokenAcquisitionException(Reason.UNEXPECTED,
                        "Microsoft Entra did not return an access token.");
            }
            return result.accessToken();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenAcquisitionException(Reason.NETWORK_UNAVAILABLE,
                    "Microsoft Entra token acquisition was interrupted.");
        } catch (ExecutionException e) {
            throw classify(e.getCause(), proxyConfigured);
        } catch (TokenAcquisitionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e, proxyConfigured);
        }
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }

    static TokenAcquisitionException classify(Throwable failure, boolean proxyConfigured) {
        Throwable root = unwrap(failure);
        String code = root instanceof MsalException && ((MsalException) root).errorCode() != null
                ? ((MsalException) root).errorCode().toLowerCase(Locale.ROOT)
                : "";
        String detail = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);

        if (containsAny(detail, "aadsts7000215", "aadsts7000222", "aadsts700027")
                || containsAny(code, "invalid_client_credential", "invalid_certificate")) {
            return new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                    "Microsoft Entra rejected the configured client credential.");
        }
        if (containsAny(detail, "aadsts90002", "aadsts900023")
                || containsAny(code, "invalid_tenant", "tenant_not_found")) {
            return new TokenAcquisitionException(Reason.INVALID_TENANT,
                    "The configured Microsoft Entra tenant is not valid.");
        }
        if (detail.contains("aadsts700016") || containsAny(code, "unauthorized_client", "client_not_found")) {
            return new TokenAcquisitionException(Reason.INVALID_CLIENT,
                    "The configured Microsoft Entra client is not valid.");
        }
        if (hasCause(root, SSLException.class)) {
            return new TokenAcquisitionException(Reason.TLS_FAILURE,
                    "TLS validation failed while contacting Microsoft Entra.");
        }
        if (hasCause(root, UnknownHostException.class)
                || containsAny(code, "unknown_host", "authority_validation_failed")) {
            return new TokenAcquisitionException(Reason.AUTHORITY_UNAVAILABLE,
                    "The Microsoft Entra authority is unavailable.");
        }
        if (hasNetworkCause(root)) {
            if (proxyConfigured) {
                return new TokenAcquisitionException(Reason.PROXY_UNAVAILABLE,
                        "The configured authentication proxy is unavailable.");
            }
            return new TokenAcquisitionException(Reason.NETWORK_UNAVAILABLE,
                    "A network failure occurred while contacting Microsoft Entra.");
        }
        if (root instanceof MsalServiceException && ((MsalServiceException) root).statusCode() >= 500) {
            return new TokenAcquisitionException(Reason.AUTHORITY_UNAVAILABLE,
                    "The Microsoft Entra authority is unavailable.");
        }
        if ("invalid_client".equals(code)) {
            return new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                    "Microsoft Entra rejected the configured client credential.");
        }
        return new TokenAcquisitionException(Reason.UNEXPECTED,
                "Unexpected Microsoft Entra authentication failure.");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure == null ? new IllegalStateException() : failure;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean hasNetworkCause(Throwable failure) {
        return hasCause(failure, ConnectException.class)
                || hasCause(failure, SocketTimeoutException.class)
                || hasCause(failure, IOException.class);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface TokenClient {
        CompletableFuture<IAuthenticationResult> acquireToken(ClientCredentialParameters parameters);
    }

    interface CredentialFactory {
        IClientCredential fromSecret(GuardedString secret) throws TokenAcquisitionException;

        IClientCredential fromCertificate(String certificatePath, String privateKeyPath)
                throws TokenAcquisitionException;
    }

    @FunctionalInterface
    interface ApplicationFactory {
        TokenClient create(
                String clientId,
                IClientCredential credential,
                String authority,
                Proxy proxy,
                SSLSocketFactory sslSocketFactory,
                ExecutorService executorService) throws Exception;
    }

    static final class DefaultCredentialFactory implements CredentialFactory {

        @Override
        public IClientCredential fromSecret(GuardedString secret) throws TokenAcquisitionException {
            if (secret == null) {
                throw new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                        "A Microsoft Entra client credential is required.");
            }
            AtomicReference<IClientCredential> credential = new AtomicReference<>();
            secret.access(clearChars -> {
                if (clearChars.length > 0) {
                    credential.set(ClientCredentialFactory.createFromSecret(new String(clearChars)));
                }
            });
            if (credential.get() == null) {
                throw new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                        "A Microsoft Entra client credential is required.");
            }
            return credential.get();
        }

        @Override
        public IClientCredential fromCertificate(String certificatePath, String privateKeyPath)
                throws TokenAcquisitionException {
            if (StringUtil.isBlank(certificatePath) || StringUtil.isBlank(privateKeyPath)) {
                throw new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                        "A certificate and private key are required for certificate authentication.");
            }
            try {
                X509Certificate certificate = loadCertificate(Path.of(certificatePath));
                PrivateKey privateKey = loadPrivateKey(Path.of(privateKeyPath));
                return ClientCredentialFactory.createFromCertificate(privateKey, certificate);
            } catch (Exception e) {
                throw new TokenAcquisitionException(Reason.INVALID_CREDENTIAL,
                        "The configured certificate or private key is not valid.");
            }
        }

        private static X509Certificate loadCertificate(Path path) throws Exception {
            requireBoundedRegularFile(path);
            try (InputStream input = Files.newInputStream(path)) {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                return (X509Certificate) certificateFactory.generateCertificate(input);
            }
        }

        private static PrivateKey loadPrivateKey(Path path) throws Exception {
            requireBoundedRegularFile(path);
            byte[] fileBytes = Files.readAllBytes(path);
            byte[] keyBytes = fileBytes;
            try {
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!fileName.endsWith(".der")) {
                    String pem = new String(fileBytes, StandardCharsets.US_ASCII)
                            .replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "")
                            .replaceAll("\\s", "");
                    keyBytes = Base64.getDecoder().decode(pem);
                }
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } finally {
                Arrays.fill(fileBytes, (byte) 0);
                if (keyBytes != fileBytes) {
                    Arrays.fill(keyBytes, (byte) 0);
                }
            }
        }

        private static void requireBoundedRegularFile(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                throw new IOException("Credential file is not a regular file");
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_CREDENTIAL_FILE_BYTES) {
                throw new IOException("Credential file size is invalid");
            }
        }
    }

    private static final class DefaultApplicationFactory implements ApplicationFactory {

        @Override
        public TokenClient create(
                String clientId,
                IClientCredential credential,
                String authority,
                Proxy proxy,
                SSLSocketFactory sslSocketFactory,
                ExecutorService executorService) throws MalformedURLException {
            ConfidentialClientApplication.Builder builder = ConfidentialClientApplication
                    .builder(clientId, credential)
                    .authority(authority)
                    .executorService(executorService)
                    .logPii(false);
            if (proxy != null) {
                builder.proxy(proxy);
            }
            if (sslSocketFactory != null) {
                builder.sslSocketFactory(sslSocketFactory);
            }
            ConfidentialClientApplication application = builder.build();
            return application::acquireToken;
        }
    }

    private static final class AuthenticationThreadFactory implements ThreadFactory {
        private static final AtomicInteger COUNTER = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "msgraph-msal-" + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
