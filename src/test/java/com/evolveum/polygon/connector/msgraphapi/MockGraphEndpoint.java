package com.evolveum.polygon.connector.msgraphapi;

public class MockGraphEndpoint extends GraphEndpoint {

    MockGraphEndpoint(MSGraphConfiguration configuration) {
        super(configuration != null ? configuration : defaultConfiguration());
    }

    private static MSGraphConfiguration defaultConfiguration() {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setDiscoverSchema(false);
        return configuration;
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
