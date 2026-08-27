package com.evolveum.polygon.connector.msgraphapi;

import com.evolveum.polygon.connector.msgraphapi.authentication.TokenProvider;

public class MockGraphEndpoint extends GraphEndpoint {

    MockGraphEndpoint(MSGraphConfiguration configuration) {
        super(configuration != null ? configuration : defaultConfiguration(), noOpTokenProvider(), null);
    }

    private static MSGraphConfiguration defaultConfiguration() {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setDiscoverSchema(false);
        return configuration;
    }

    private static TokenProvider noOpTokenProvider() {
        return new TokenProvider() {
            @Override
            public String acquireToken(String scope) {
                return "unit-test-token";
            }

            @Override
            public String reacquireToken(String scope) {
                return "unit-test-token";
            }
        };
    }

    @Override
    protected void authenticate() {
        // Do nothing
    }

    @Override
    protected void initHttpClient() {
        // Do nothing
    }
}
