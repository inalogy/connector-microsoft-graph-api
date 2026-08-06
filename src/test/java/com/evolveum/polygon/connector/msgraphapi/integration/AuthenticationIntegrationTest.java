package com.evolveum.polygon.connector.msgraphapi.integration;

import com.evolveum.polygon.connector.msgraphapi.MSGraphConfiguration;
import com.evolveum.polygon.connector.msgraphapi.MSGraphConnector;
import org.identityconnectors.common.security.GuardedString;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Optional non-production tenant checks. Credentials are supplied through the
 * process environment and are never read from the tracked test properties.
 */
public class AuthenticationIntegrationTest {

    @Test(groups = "authentication-integration")
    public void authenticatesWithClientSecret() {
        String clientId = requiredEnvironment("MSGRAPH_TEST_CLIENT_ID");
        String tenantId = requiredEnvironment("MSGRAPH_TEST_TENANT_ID");
        GuardedString clientSecret = new GuardedString(
                requiredEnvironment("MSGRAPH_TEST_CLIENT_SECRET").toCharArray());

        MSGraphConfiguration configuration = baseConfiguration(clientId, tenantId);
        configuration.setClientSecret(clientSecret);
        try {
            testConfiguration(configuration);
        } finally {
            clientSecret.dispose();
        }
    }

    @Test(groups = "authentication-integration")
    public void authenticatesWithCertificate() {
        MSGraphConfiguration configuration = baseConfiguration(
                requiredEnvironment("MSGRAPH_TEST_CLIENT_ID"),
                requiredEnvironment("MSGRAPH_TEST_TENANT_ID"));
        configuration.setCertificateBasedAuthentication(true);
        configuration.setCertificatePath(requiredEnvironment("MSGRAPH_TEST_CERTIFICATE_PATH"));
        configuration.setPrivateKeyPath(requiredEnvironment("MSGRAPH_TEST_PRIVATE_KEY_PATH"));
        testConfiguration(configuration);
    }

    private static MSGraphConfiguration baseConfiguration(String clientId, String tenantId) {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setClientId(clientId);
        configuration.setTenantId(tenantId);
        configuration.setDiscoverSchema(false);
        configuration.setValidateWithFailoverTrust(false);
        return configuration;
    }

    private static void testConfiguration(MSGraphConfiguration configuration) {
        MSGraphConnector connector = new MSGraphConnector();
        connector.init(configuration);
        try {
            connector.test();
        } finally {
            connector.dispose();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new SkipException("Required authentication integration-test environment is not configured: " + name);
        }
        return value;
    }
}
