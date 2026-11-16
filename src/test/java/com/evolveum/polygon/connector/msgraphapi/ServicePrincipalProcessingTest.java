package com.evolveum.polygon.connector.msgraphapi;

import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.annotations.Test;

import java.util.*;

import static org.testng.AssertJUnit.*;

/**
 * Test case for {@link ServicePrincipalProcessing}
 */
@Test(groups = "unit")
public class ServicePrincipalProcessingTest {

    private static final String DEFAULT_FILTER = MSGraphConfiguration.DEFAULT_SERVICE_PRINCIPAL_FILTER;

    private static class RecordingGraphEndpoint extends MockGraphEndpoint {
        private final Map<String, JSONObject> objects = new HashMap<>();
        private final Map<String, List<JSONObject>> lists = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private final List<String> requests = new ArrayList<>();
        private final List<Boolean> paging = new ArrayList<>();

        RecordingGraphEndpoint(MSGraphConfiguration configuration) {
            super(configuration);
        }

        @Override
        protected JSONObject executeGetRequest(String path, String customQuery, OperationOptions options) {
            requests.add(path + (customQuery == null ? "" : "?" + customQuery));
            JSONObject object = objects.get(path);
            if (object == null) {
                throw new UnknownUidException("HTTP error 404 Not Found");
            }
            return new JSONObject(object.toString());
        }

        @Override
        protected void executeListRequest(String path, String customQuery, OperationOptions options,
                                          boolean paging, ObjectProcessing.JSONObjectHandler handler) {
            String request = path + (customQuery == null ? "" : "?" + customQuery);
            requests.add(request);
            this.paging.add(paging);
            if (missing.contains(path)) {
                throw new UnknownUidException("HTTP error 404 Not Found");
            }
            for (JSONObject object : lists.getOrDefault(request, Collections.emptyList())) {
                if (!handler.handle(options, new JSONObject(object.toString()))) {
                    return;
                }
            }
        }
    }

    private static JSONObject app(String id, String displayName) {
        JSONObject app = new JSONObject()
                .put("id", id)
                .put("appId", "app-" + id)
                .put("accountEnabled", true)
                .put("appRoleAssignmentRequired", false)
                .put("servicePrincipalType", "Application")
                .put("tags", new JSONArray().put("WindowsAzureActiveDirectoryIntegratedApp"))
                .put("servicePrincipalNames", new JSONArray().put("api://" + id).put("app-" + id))
                .put("appRoles", new JSONArray()
                        .put(new JSONObject().put("id", "role-1").put("value", "Reader").put("displayName", "Readers")))
                .put("homepage", JSONObject.NULL);
        if (displayName != null) {
            app.put("displayName", displayName);
        }
        return app;
    }

    private static JSONObject assignment(String principalId, String principalType) {
        return new JSONObject().put("principalId", principalId).put("principalType", principalType);
    }

    private static String listQuery(String filter) {
        return "/servicePrincipals?$filter=" + filter + "&$count=true";
    }

    private static List<ConnectorObject> search(RecordingGraphEndpoint endpoint, Filter filter, OperationOptions options) {
        List<ConnectorObject> results = new ArrayList<>();
        new ServicePrincipalProcessing(endpoint).executeQueryForServicePrincipal(filter, results::add, options);
        return results;
    }

    private static OperationOptions defaults() {
        return new OperationOptionsBuilder().setReturnDefaultAttributes(true).build();
    }

    private static OperationOptions withAssignedGroups() {
        return new OperationOptionsBuilder()
                .setReturnDefaultAttributes(true)
                .setAttributesToGet(ServicePrincipalProcessing.ATTR_ASSIGNED_GROUPS)
                .build();
    }

    @Test
    public void testSchema() {
        Schema schema = new MockGraphEndpoint(null).getSchemaTranslator().getConnIdSchema();
        ObjectClassInfo info = schema.findObjectClassInfo(ServicePrincipalProcessing.OBJECT_CLASS_NAME);
        assertNotNull(info);

        Map<String, AttributeInfo> attrs = new HashMap<>();
        info.getAttributeInfo().forEach(a -> attrs.put(a.getName(), a));

        AttributeInfo assignedGroups = attrs.get(ServicePrincipalProcessing.ATTR_ASSIGNED_GROUPS);
        assertTrue(assignedGroups.isMultiValued());
        assertFalse(assignedGroups.isReturnedByDefault());
        assertFalse(assignedGroups.isUpdateable());
        assertEquals(Boolean.class, attrs.get(ServicePrincipalProcessing.ATTR_ACCOUNT_ENABLED).getType());
        assertTrue(attrs.get(ServicePrincipalProcessing.ATTR_APP_ROLES__ID).isMultiValued());
        for (AttributeInfo attr : info.getAttributeInfo()) {
            if (!attr.getName().startsWith("__")) {
                assertFalse(attr.getName() + " must be read-only", attr.isCreateable() || attr.isUpdateable());
            }
        }
    }

    @Test
    public void testListUsesConfiguredFilterAndPaging() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.lists.put(listQuery(DEFAULT_FILTER), List.of(app("sp1", "Saml-Teleport"), app("sp2", null)));

        List<ConnectorObject> results = search(endpoint, null, defaults());

        assertEquals(List.of(listQuery(DEFAULT_FILTER)), endpoint.requests);
        assertEquals(List.of(true), endpoint.paging);
        assertEquals(2, results.size());

        ConnectorObject first = results.get(0);
        assertEquals("sp1", first.getUid().getUidValue());
        assertEquals("Saml-Teleport", first.getName().getNameValue());
        assertEquals("app-sp1", AttributeUtil.getStringValue(first.getAttributeByName("appId")));
        assertEquals(Boolean.TRUE, AttributeUtil.getBooleanValue(first.getAttributeByName("accountEnabled")));
        assertEquals(List.of("api://sp1", "app-sp1"), first.getAttributeByName("servicePrincipalNames").getValue());
        assertEquals(List.of("role-1"), first.getAttributeByName("appRoles.id").getValue());
        assertEquals(List.of("Reader"), first.getAttributeByName("appRoles.value").getValue());
        assertNull("JSON null must not become a value", first.getAttributeByName("homepage"));
        assertNull("assignedGroups is fetched only on request", first.getAttributeByName("assignedGroups"));

        assertEquals("an application without displayName is named by its id",
                "sp2", results.get(1).getName().getNameValue());
    }

    @Test
    public void testListSkipsDuplicateIds() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.lists.put(listQuery(DEFAULT_FILTER), List.of(app("sp1", "A"), app("sp1", "A"), app("sp2", "B")));

        assertEquals(2, search(endpoint, null, defaults()).size());
    }

    @Test
    public void testBlankFilterListsAllServicePrincipals() {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setServicePrincipalFilter(" ");
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(configuration);
        endpoint.lists.put("/servicePrincipals", List.of(app("sp1", "A")));

        assertEquals(1, search(endpoint, null, defaults()).size());
        assertEquals(List.of("/servicePrincipals"), endpoint.requests);
    }

    @Test
    public void testGetByUidReadsFullObjectAndAssignedGroups() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.objects.put("/servicePrincipals/sp1", app("sp1", "Saml-Teleport"));
        endpoint.lists.put("/servicePrincipals/sp1/appRoleAssignedTo?$select=principalId,principalType", List.of(
                assignment("g1", "Group"),
                assignment("u1", "User"),
                assignment("g1", "Group"),
                assignment("s1", "ServicePrincipal"),
                assignment("g2", "Group")));

        List<ConnectorObject> results = search(endpoint, FilterBuilder.equalTo(new Uid("sp1")), withAssignedGroups());

        assertEquals(1, results.size());
        assertEquals(List.of("g1", "g2"), results.get(0).getAttributeByName("assignedGroups").getValue());
        assertEquals("/servicePrincipals/sp1", endpoint.requests.get(0));
        assertEquals("assignments must be read across all pages", List.of(true), endpoint.paging);
    }

    @Test
    public void testAssignedGroupsEmptyWhenNoGroupIsAssigned() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.objects.put("/servicePrincipals/sp1", app("sp1", "A"));

        List<ConnectorObject> results = search(endpoint, FilterBuilder.equalTo(new Uid("sp1")), withAssignedGroups());

        Attribute assignedGroups = results.get(0).getAttributeByName("assignedGroups");
        assertNotNull("requested attribute is returned even when empty", assignedGroups);
        assertTrue(assignedGroups.getValue().isEmpty());
    }

    @Test(expectedExceptions = UnknownUidException.class)
    public void testGetByUidOfMissingApplication() {
        search(new RecordingGraphEndpoint(null), FilterBuilder.equalTo(new Uid("gone")), defaults());
    }

    @Test
    public void testNameFilterIsEscaped() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);

        search(endpoint, FilterBuilder.equalTo(new Name("O'Brien app")), defaults());

        assertEquals(List.of(listQuery("(" + DEFAULT_FILTER + ") and displayName eq 'O''Brien app'")),
                endpoint.requests);
    }

    @Test
    public void testAppIdFilter() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);

        search(endpoint, FilterBuilder.equalTo(AttributeBuilder.build("appId", "client-1")), defaults());

        assertEquals(List.of(listQuery("(" + DEFAULT_FILTER + ") and appId eq 'client-1'")), endpoint.requests);
    }

    @Test
    public void testAssignedGroupFilterReadsGroupSide() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.lists.put("/groups/g1/appRoleAssignments?$select=resourceId", List.of(
                new JSONObject().put("resourceId", "sp1"),
                new JSONObject().put("resourceId", "sp1"),
                new JSONObject().put("resourceId", "spNotIntegrated")));
        endpoint.lists.put(listQuery("(" + DEFAULT_FILTER + ") and id eq 'sp1'"), List.of(app("sp1", "A")));

        List<ConnectorObject> results = search(endpoint,
                FilterBuilder.containsAllValues(AttributeBuilder.build("assignedGroups", "g1")), defaults());

        assertEquals(1, results.size());
        assertEquals("sp1", results.get(0).getUid().getUidValue());
        assertEquals(List.of(
                "/groups/g1/appRoleAssignments?$select=resourceId",
                listQuery("(" + DEFAULT_FILTER + ") and id eq 'sp1'"),
                listQuery("(" + DEFAULT_FILTER + ") and id eq 'spNotIntegrated'")), endpoint.requests);
    }

    @Test
    public void testAssignedGroupFilterForMissingGroup() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.missing.add("/groups/gone/appRoleAssignments");

        List<ConnectorObject> results = search(endpoint,
                FilterBuilder.equalTo(AttributeBuilder.build("assignedGroups", "gone")), defaults());

        assertTrue(results.isEmpty());
    }

    @Test
    public void testUntranslatedFilterIsEvaluatedLocally() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.lists.put(listQuery(DEFAULT_FILTER), List.of(app("sp1", "Saml-Teleport"), app("sp2", "Gitlab")));

        List<ConnectorObject> results = search(endpoint,
                FilterBuilder.startsWith(AttributeBuilder.build("displayName", "Saml")), defaults());

        assertEquals(1, results.size());
        assertEquals("sp1", results.get(0).getUid().getUidValue());
    }

    @Test
    public void testHandlerCanStopTheListing() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint(null);
        endpoint.lists.put(listQuery(DEFAULT_FILTER), List.of(app("sp1", "A"), app("sp2", "B")));

        List<ConnectorObject> results = new ArrayList<>();
        new ServicePrincipalProcessing(endpoint).executeQueryForServicePrincipal(null, obj -> {
            results.add(obj);
            return false;
        }, defaults());

        assertEquals(1, results.size());
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testUpdateIsRejected() {
        new MSGraphConnector().updateDelta(ServicePrincipalProcessing.OBJECT_CLASS, new Uid("sp1"),
                Set.of(AttributeDeltaBuilder.build("assignedGroups", List.of("g1"), null)),
                new OperationOptionsBuilder().build());
    }
}
