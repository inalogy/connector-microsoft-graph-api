package com.evolveum.polygon.connector.msgraphapi.authentication;

import com.evolveum.polygon.connector.msgraphapi.MSGraphConfiguration;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCertificate;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.IClientSecret;
import com.microsoft.aad.msal4j.ITenantProfile;
import com.microsoft.aad.msal4j.MsalClientException;
import com.microsoft.aad.msal4j.MsalServiceException;
import org.identityconnectors.common.security.GuardedString;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Test(groups = "unit")
public class MsalTokenProviderTest {

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    public void constructsSecretProviderAndAcquiresToken() throws Exception {
        RecordingApplicationFactory applicationFactory = new RecordingApplicationFactory(successfulResult());
        MsalTokenProvider provider = new MsalTokenProvider(
                secretConfiguration(), null, new MsalTokenProvider.DefaultCredentialFactory(), applicationFactory);
        try {
            Assert.assertEquals(provider.acquireToken(GRAPH_SCOPE), "test-access-token");
            Assert.assertEquals(applicationFactory.creationCount, 1);
            Assert.assertTrue(applicationFactory.credential instanceof IClientSecret);
            Assert.assertEquals(applicationFactory.authority,
                    "https://login.microsoftonline.com/test-tenant");
            Assert.assertNull(applicationFactory.proxy);
        } finally {
            provider.close();
        }
    }

    public void constructsCertificateProviderAndAcquiresToken() throws Exception {
        Path certificateFile = Files.createTempFile("msgraph-public-certificate-", ".der");
        Path privateKeyFile = Files.createTempFile("msgraph-private-key-", ".der");
        try {
            Files.write(certificateFile, loadJvmTrustCertificate().getEncoded());
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            Files.write(privateKeyFile, keyPair.getPrivate().getEncoded());

            MSGraphConfiguration configuration = secretConfiguration();
            configuration.setCertificateBasedAuthentication(true);
            configuration.setCertificatePath(certificateFile.toString());
            configuration.setPrivateKeyPath(privateKeyFile.toString());

            RecordingApplicationFactory applicationFactory = new RecordingApplicationFactory(successfulResult());
            MsalTokenProvider provider = new MsalTokenProvider(
                    configuration, null, new MsalTokenProvider.DefaultCredentialFactory(), applicationFactory);
            try {
                Assert.assertEquals(provider.acquireToken(GRAPH_SCOPE), "test-access-token");
                Assert.assertTrue(applicationFactory.credential instanceof IClientCertificate);
                Assert.assertEquals(applicationFactory.creationCount, 1);
            } finally {
                provider.close();
            }
        } finally {
            Files.deleteIfExists(certificateFile);
            Files.deleteIfExists(privateKeyFile);
        }
    }

    public void reusesApplicationAndControlsMsalCacheBypass() throws Exception {
        RecordingApplicationFactory applicationFactory = new RecordingApplicationFactory(successfulResult());
        MsalTokenProvider provider = new MsalTokenProvider(
                secretConfiguration(), null, new MsalTokenProvider.DefaultCredentialFactory(), applicationFactory);
        try {
            Assert.assertEquals(provider.acquireToken(GRAPH_SCOPE), "test-access-token");
            Assert.assertEquals(provider.acquireToken(GRAPH_SCOPE), "test-access-token");
            Assert.assertEquals(provider.reacquireToken(GRAPH_SCOPE), "test-access-token");

            Assert.assertEquals(applicationFactory.creationCount, 1);
            Assert.assertEquals(applicationFactory.parameters.size(), 3);
            Assert.assertFalse(applicationFactory.parameters.get(0).skipCache());
            Assert.assertFalse(applicationFactory.parameters.get(1).skipCache());
            Assert.assertTrue(applicationFactory.parameters.get(2).skipCache());
            Assert.assertEquals(applicationFactory.parameters.get(0).scopes(),
                    Collections.singleton(GRAPH_SCOPE));
        } finally {
            provider.close();
        }
    }

    public void classifiesInvalidTenant() throws Exception {
        assertAcquisitionFailure(
                new MsalServiceException("AADSTS90002: tenant not found", "invalid_request"),
                false,
                TokenAcquisitionException.Reason.INVALID_TENANT);
    }

    public void classifiesInvalidClientId() throws Exception {
        assertAcquisitionFailure(
                new MsalServiceException("AADSTS700016: application not found", "unauthorized_client"),
                false,
                TokenAcquisitionException.Reason.INVALID_CLIENT);
    }

    public void classifiesInvalidClientSecret() throws Exception {
        assertAcquisitionFailure(
                new MsalServiceException("AADSTS7000215: credential rejected", "invalid_client"),
                false,
                TokenAcquisitionException.Reason.INVALID_CREDENTIAL);
    }

    public void rejectsInvalidCertificate() throws Exception {
        Path certificateFile = Files.createTempFile("msgraph-invalid-certificate-", ".der");
        Path privateKeyFile = Files.createTempFile("msgraph-private-key-", ".der");
        try {
            Files.writeString(certificateFile, "not-a-certificate");
            Files.writeString(privateKeyFile, "not-a-private-key");
            assertInvalidCertificateConfiguration(certificateFile, privateKeyFile);
        } finally {
            Files.deleteIfExists(certificateFile);
            Files.deleteIfExists(privateKeyFile);
        }
    }

    public void rejectsInvalidPrivateKey() throws Exception {
        Path certificateFile = Files.createTempFile("msgraph-public-certificate-", ".der");
        Path privateKeyFile = Files.createTempFile("msgraph-invalid-private-key-", ".der");
        try {
            Files.write(certificateFile, loadJvmTrustCertificate().getEncoded());
            Files.writeString(privateKeyFile, "not-a-private-key");
            assertInvalidCertificateConfiguration(certificateFile, privateKeyFile);
        } finally {
            Files.deleteIfExists(certificateFile);
            Files.deleteIfExists(privateKeyFile);
        }
    }

    public void classifiesUnavailableAuthority() throws Exception {
        assertAcquisitionFailure(
                new MsalClientException(new UnknownHostException("authority unavailable")),
                false,
                TokenAcquisitionException.Reason.AUTHORITY_UNAVAILABLE);
    }

    public void classifiesProxyFailure() throws Exception {
        assertAcquisitionFailure(
                new MsalClientException(new ConnectException("proxy unavailable")),
                true,
                TokenAcquisitionException.Reason.PROXY_UNAVAILABLE);
    }

    public void classifiesNetworkFailure() throws Exception {
        assertAcquisitionFailure(
                new MsalClientException(new ConnectException("network unavailable")),
                false,
                TokenAcquisitionException.Reason.NETWORK_UNAVAILABLE);
    }

    public void classifiesTlsFailure() throws Exception {
        assertAcquisitionFailure(
                new MsalClientException(new SSLHandshakeException("certificate validation failed")),
                false,
                TokenAcquisitionException.Reason.TLS_FAILURE);
    }

    public void exceptionTextDoesNotExposeProviderDetails() throws Exception {
        String sensitiveMarker = "sensitive-provider-detail";
        TokenAcquisitionException failure = acquisitionFailure(
                new IllegalStateException(sensitiveMarker), false);

        Assert.assertFalse(failure.getMessage().contains(sensitiveMarker));
        Assert.assertFalse(failure.toString().contains(sensitiveMarker));
        Assert.assertNull(failure.getCause());
    }

    private void assertInvalidCertificateConfiguration(Path certificateFile, Path privateKeyFile) throws Exception {
        MSGraphConfiguration configuration = secretConfiguration();
        configuration.setCertificateBasedAuthentication(true);
        configuration.setCertificatePath(certificateFile.toString());
        configuration.setPrivateKeyPath(privateKeyFile.toString());
        try {
            new MsalTokenProvider(
                    configuration,
                    null,
                    new MsalTokenProvider.DefaultCredentialFactory(),
                    new RecordingApplicationFactory(successfulResult()));
            Assert.fail("Invalid certificate configuration was accepted");
        } catch (TokenAcquisitionException e) {
            Assert.assertEquals(e.getReason(), TokenAcquisitionException.Reason.INVALID_CREDENTIAL);
        }
    }

    private void assertAcquisitionFailure(
            Throwable providerFailure,
            boolean proxyConfigured,
            TokenAcquisitionException.Reason expectedReason) throws Exception {
        Assert.assertEquals(acquisitionFailure(providerFailure, proxyConfigured).getReason(), expectedReason);
    }

    private TokenAcquisitionException acquisitionFailure(Throwable providerFailure, boolean proxyConfigured)
            throws Exception {
        MSGraphConfiguration configuration = secretConfiguration();
        if (proxyConfigured) {
            configuration.setProxyHost("127.0.0.1");
            configuration.setProxyPort("6553");
        }
        RecordingApplicationFactory applicationFactory = new RecordingApplicationFactory(providerFailure);
        MsalTokenProvider provider = new MsalTokenProvider(
                configuration, null, new MsalTokenProvider.DefaultCredentialFactory(), applicationFactory);
        try {
            provider.acquireToken(GRAPH_SCOPE);
            Assert.fail("Expected token acquisition to fail");
            return null;
        } catch (TokenAcquisitionException e) {
            return e;
        } finally {
            provider.close();
        }
    }

    private static MSGraphConfiguration secretConfiguration() {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setClientId("test-client");
        configuration.setTenantId("test-tenant");
        configuration.setClientSecret(new GuardedString("test-secret".toCharArray()));
        configuration.setDiscoverSchema(false);
        return configuration;
    }

    private static X509Certificate loadJvmTrustCertificate() throws Exception {
        Path trustStore = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream input = Files.newInputStream(trustStore)) {
            keyStore.load(input, "changeit".toCharArray());
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            java.security.cert.Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate instanceof X509Certificate) {
                return (X509Certificate) certificate;
            }
        }
        throw new IllegalStateException("No X.509 trust certificate is available for the test");
    }

    private static IAuthenticationResult successfulResult() {
        return new IAuthenticationResult() {
            @Override
            public String accessToken() {
                return "test-access-token";
            }

            @Override
            public String idToken() {
                return null;
            }

            @Override
            public IAccount account() {
                return null;
            }

            @Override
            public ITenantProfile tenantProfile() {
                return null;
            }

            @Override
            public String environment() {
                return null;
            }

            @Override
            public String scopes() {
                return GRAPH_SCOPE;
            }

            @Override
            public Date expiresOnDate() {
                return new Date(System.currentTimeMillis() + 60_000L);
            }
        };
    }

    private static final class RecordingApplicationFactory implements MsalTokenProvider.ApplicationFactory {
        private final IAuthenticationResult result;
        private final Throwable failure;
        private final List<ClientCredentialParameters> parameters = new ArrayList<>();
        private int creationCount;
        private IClientCredential credential;
        private String authority;
        private Proxy proxy;

        private RecordingApplicationFactory(IAuthenticationResult result) {
            this.result = result;
            this.failure = null;
        }

        private RecordingApplicationFactory(Throwable failure) {
            this.result = null;
            this.failure = failure;
        }

        @Override
        public MsalTokenProvider.TokenClient create(
                String clientId,
                IClientCredential credential,
                String authority,
                Proxy proxy,
                javax.net.ssl.SSLSocketFactory sslSocketFactory,
                ExecutorService executorService) {
            creationCount++;
            this.credential = credential;
            this.authority = authority;
            this.proxy = proxy;
            return tokenParameters -> {
                parameters.add(tokenParameters);
                if (failure == null) {
                    return CompletableFuture.completedFuture(result);
                }
                CompletableFuture<IAuthenticationResult> future = new CompletableFuture<>();
                future.completeExceptionally(failure);
                return future;
            };
        }
    }
}
