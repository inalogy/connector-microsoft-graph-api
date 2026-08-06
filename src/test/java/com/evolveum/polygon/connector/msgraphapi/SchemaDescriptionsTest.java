package com.evolveum.polygon.connector.msgraphapi;

import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.Schema;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

@Test(groups = "unit")
public class SchemaDescriptionsTest {

    @Test
    public void testStaticObjectClassDescriptions() {
        Schema schema = new MockGraphEndpoint(null).getSchemaTranslator().getConnIdSchema();

        assertDescription(schema, ObjectClass.ACCOUNT_NAME, "Microsoft Entra ID user account");
        assertDescription(schema, ObjectClass.GROUP_NAME, "Microsoft Entra ID group");
        assertDescription(schema, RoleProcessing.ROLE_NAME, "Microsoft Entra ID directory role");
        assertDescription(schema, LicenseProcessing.OBJECT_CLASS_NAME,
                "Microsoft 365 subscribed license SKU");
    }

    @Test
    public void testDiscoveredListObjectClassDescription() {
        Schema schema = new SchemaDiscoveryGraphEndpoint().getSchemaTranslator().getConnIdSchema();

        assertDescription(schema, "site-name~list-name",
                "Microsoft SharePoint list item in site 'site-name', list 'list-name'");
    }

    private static void assertDescription(Schema schema, String objectClassType, String expectedDescription) {
        ObjectClassInfo objectClassInfo = schema.findObjectClassInfo(objectClassType);
        assertNotNull("Object class is missing: " + objectClassType, objectClassInfo);
        assertEquals("Unexpected description for " + objectClassType,
                expectedDescription, objectClassInfo.getDescription());
    }

    private static class SchemaDiscoveryGraphEndpoint extends MockGraphEndpoint {

        private SchemaDiscoveryGraphEndpoint() {
            super(discoveryConfiguration());
        }

        private static MSGraphConfiguration discoveryConfiguration() {
            MSGraphConfiguration configuration = new MSGraphConfiguration();
            configuration.setDiscoverSchema(true);
            return configuration;
        }

        @Override
        protected JSONArray executeListRequest(
                String path, String customQuery, OperationOptions options, boolean paging) {
            return switch (path) {
                case "/sites" -> new JSONArray()
                        .put(new JSONObject()
                                .put("id", "site-id")
                                .put("name", "site-name")
                                .put("isPersonalSite", false));
                case "/sites/site-id/lists" -> new JSONArray()
                        .put(new JSONObject()
                                .put("id", "list-id")
                                .put("name", "list-name"));
                case "/sites/site-id/lists/list-id/columns" -> new JSONArray()
                        .put(new JSONObject()
                                .put("name", "Title")
                                .put("readOnly", false)
                                .put("required", false));
                default -> throw new AssertionError("Unexpected Graph path: " + path);
            };
        }
    }
}
