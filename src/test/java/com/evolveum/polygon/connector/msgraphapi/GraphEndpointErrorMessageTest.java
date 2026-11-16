package com.evolveum.polygon.connector.msgraphapi;

import org.testng.annotations.Test;

import static org.testng.AssertJUnit.*;

/**
 * Test case for the exception messages built from failed Graph responses.
 */
@Test(groups = "unit")
public class GraphEndpointErrorMessageTest {

    @Test
    public void testGraphErrorCodeAndMessageAreIncluded() {
        String body = "{\"error\":{\"code\":\"Request_BadRequest\",\"message\":\"The role is not assignable.\"}}";

        assertEquals("HTTP error 400 Bad Request (provider code: Request_BadRequest): The role is not assignable.",
                GraphEndpoint.buildErrorMessage(400, "Bad Request", body));
    }

    @Test
    public void testGraphMessageIsOneLineAndCapped() {
        StringBuilder longText = new StringBuilder("first line\n   second line ");
        for (int i = 0; i < 100; i++) {
            longText.append("0123456789");
        }
        String body = "{\"error\":{\"code\":\"Request_BadRequest\",\"message\":\"" + longText.toString().replace("\n", "\\n") + "\"}}";

        String message = GraphEndpoint.buildErrorMessage(400, "Bad Request", body);

        assertTrue(message, message.contains(": first line second line 0123"));
        assertFalse(message, message.contains("\n"));
        assertTrue(message, message.endsWith("..."));
        String providerPart = message.substring(message.indexOf("): ") + 3);
        assertEquals(GraphEndpoint.MAX_PROVIDER_MESSAGE_LENGTH + 3, providerPart.length());
    }

    @Test
    public void testNonGraphBodyGivesStatusOnly() {
        assertEquals("HTTP error 502 Bad Gateway", GraphEndpoint.buildErrorMessage(502, "Bad Gateway", "<html>gateway</html>"));
        assertEquals("HTTP error 404 Not Found", GraphEndpoint.buildErrorMessage(404, "Not Found", null));
    }

    @Test
    public void testCodeWithoutMessage() {
        assertEquals("HTTP error 404 Not Found (provider code: Request_ResourceNotFound)",
                GraphEndpoint.buildErrorMessage(404, "Not Found", "{\"error\":{\"code\":\"Request_ResourceNotFound\"}}"));
    }
}
