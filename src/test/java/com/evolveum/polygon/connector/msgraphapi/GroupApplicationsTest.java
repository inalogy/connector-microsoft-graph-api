package com.evolveum.polygon.connector.msgraphapi;

import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.json.JSONObject;
import org.testng.annotations.Test;

import java.util.*;

import static com.evolveum.polygon.connector.msgraphapi.GroupProcessing.ATTR_APPLICATIONS;
import static org.testng.AssertJUnit.*;

/**
 * Test case for the {@code applications} attribute of {@link GroupProcessing}
 */
@Test(groups = "unit")
public class GroupApplicationsTest {

    private static class RecordingGraphEndpoint extends MockGraphEndpoint {
        private final Map<String, JSONObject> objects = new HashMap<>();
        private final Map<String, List<JSONObject>> lists = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        // path -> number of further POSTs that still fail as unknown (a new group not replicated yet)
        private final Map<String, Integer> postsUnknownFor = new HashMap<>();
        // paths whose POST answers that the object already exists
        private final Set<String> postsAlreadyExist = new HashSet<>();
        private final List<String> requests = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();
        private final List<JSONObject> bodies = new ArrayList<>();

        RecordingGraphEndpoint() {
            super(null);
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
            if (missing.contains(path)) {
                throw new UnknownUidException("HTTP error 404 Not Found");
            }
            for (JSONObject object : lists.getOrDefault(request, Collections.emptyList())) {
                if (!handler.handle(options, new JSONObject(object.toString()))) {
                    return;
                }
            }
        }

        @Override
        public JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject json, Boolean parseResult) {
            String path = request.getURI().getPath();
            writes.add(request.getMethod() + " " + path);
            bodies.add(json);
            Integer stillUnknown = postsUnknownFor.get(path);
            if (stillUnknown != null && stillUnknown > 0) {
                postsUnknownFor.put(path, stillUnknown - 1);
                throw new UnknownUidException("HTTP error 400 Bad Request (Invalid object identifier)");
            }
            if (postsAlreadyExist.contains(path)) {
                throw new AlreadyExistsException("HTTP error 409 Conflict");
            }
            return null;
        }

        @Override
        protected JSONObject callRequest(HttpRequestBase request, boolean parseResult) {
            writes.add(request.getMethod() + " " + request.getURI().getPath());
            return null;
        }

        @Override
        protected void callRequestNoContentNoJson(HttpEntityEnclosingRequestBase request, List<JSONObject> jsonObjects) {
            // group PATCH, not under test here
        }
    }

    private static JSONObject group(String id, String displayName) {
        return new JSONObject().put("id", id).put("displayName", displayName).put("securityEnabled", true);
    }

    private static JSONObject assignedTo(String principalId, String principalType) {
        return new JSONObject().put("principalId", principalId).put("principalType", principalType);
    }

    private static JSONObject assignment(String id, String resourceId) {
        return new JSONObject().put("id", id).put("resourceId", resourceId).put("resourceDisplayName", "App " + resourceId);
    }

    private static String assignedToQuery(String applicationId) {
        return "/servicePrincipals/" + applicationId + "/appRoleAssignedTo?$select=principalId,principalType";
    }

    private static String applicationsQuery(String groupId) {
        return "/groups/" + groupId + "/appRoleAssignments?$select=resourceId,resourceDisplayName";
    }

    private static OperationOptions defaults() {
        return new OperationOptionsBuilder().setReturnDefaultAttributes(true).build();
    }

    private static OperationOptions withApplications() {
        return new OperationOptionsBuilder().setReturnDefaultAttributes(true).setAttributesToGet(ATTR_APPLICATIONS).build();
    }

    private static List<ConnectorObject> searchByApplication(RecordingGraphEndpoint endpoint, Filter filter) {
        List<ConnectorObject> results = new ArrayList<>();
        assertTrue(new GroupProcessing(endpoint).executeQueryForGroupByApplication(filter, results::add, defaults()));
        return results;
    }

    private static Set<String> names(List<ConnectorObject> objects) {
        Set<String> names = new LinkedHashSet<>();
        objects.forEach(o -> names.add(o.getName().getNameValue()));
        return names;
    }

    @Test
    public void testSchema() {
        Schema schema = new MockGraphEndpoint(null).getSchemaTranslator().getConnIdSchema();
        AttributeInfo applications = schema.findObjectClassInfo(ObjectClass.GROUP_NAME).getAttributeInfo().stream()
                .filter(a -> ATTR_APPLICATIONS.equals(a.getName())).findFirst().orElse(null);

        assertNotNull(applications);
        assertTrue(applications.isMultiValued());
        assertTrue(applications.isCreateable());
        assertTrue(applications.isUpdateable());
        assertFalse(applications.isReturnedByDefault());
    }

    @Test
    public void testApplicationsReadOnRequestOnly() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(applicationsQuery("g1"),
                List.of(assignment("a1", "sp1"), assignment("a2", "sp1"), assignment("a3", "sp2")));
        GroupProcessing processing = new GroupProcessing(endpoint);

        List<ConnectorObject> results = new ArrayList<>();
        processing.handleJSONObject(defaults(), group("g1", "G1"), results::add);
        assertNull(results.get(0).getAttributeByName(ATTR_APPLICATIONS));
        assertTrue(endpoint.requests.isEmpty());

        processing.handleJSONObject(withApplications(), group("g1", "G1"), results::add);
        assertEquals("an application with several roles is listed once",
                List.of("sp1", "sp2"), results.get(1).getAttributeByName(ATTR_APPLICATIONS).getValue());
        assertEquals(List.of("App sp1", "App sp2"),
                results.get(1).getAttributeByName(GroupProcessing.ATTR_APPLICATION_NAMES).getValue());
    }

    @Test
    public void testApplicationNamesAloneLoadTheAssignments() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(applicationsQuery("g1"), List.of(assignment("a1", "sp1")));
        OperationOptions options = new OperationOptionsBuilder().setReturnDefaultAttributes(true)
                .setAttributesToGet(GroupProcessing.ATTR_APPLICATION_NAMES).build();

        List<ConnectorObject> results = new ArrayList<>();
        new GroupProcessing(endpoint).handleJSONObject(options, group("g1", "G1"), results::add);

        assertEquals(List.of("App sp1"), results.get(0).getAttributeByName(GroupProcessing.ATTR_APPLICATION_NAMES).getValue());
    }

    @Test
    public void testSearchByApplicationReadsItsAssignedGroups() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(assignedToQuery("sp1"), List.of(
                assignedTo("g1", "Group"), assignedTo("u1", "User"), assignedTo("g2", "Group"), assignedTo("g1", "Group")));
        endpoint.objects.put("/groups/g1", group("g1", "App-Readers"));
        endpoint.objects.put("/groups/g2", group("g2", "App-Admins"));
        endpoint.lists.put(applicationsQuery("g1"), List.of(assignment("a1", "sp1")));
        endpoint.lists.put(applicationsQuery("g2"), List.of(assignment("a2", "sp1"), assignment("a3", "sp9")));

        List<ConnectorObject> results = searchByApplication(endpoint,
                FilterBuilder.equalTo(AttributeBuilder.build(ATTR_APPLICATIONS, "sp1")));

        assertEquals(Set.of("App-Readers", "App-Admins"), names(results));
        assertEquals(List.of("sp1", "sp9"), results.get(1).getAttributeByName(ATTR_APPLICATIONS).getValue());
        assertFalse("users assigned to the application are not groups", endpoint.requests.contains("/groups/u1"));
    }

    @Test
    public void testSearchByApplicationAppliesTheRestOfTheFilter() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(assignedToQuery("sp1"), List.of(assignedTo("g1", "Group"), assignedTo("g2", "Group")));
        endpoint.objects.put("/groups/g1", group("g1", "App-Readers"));
        endpoint.objects.put("/groups/g2", group("g2", "App-Admins"));
        endpoint.lists.put(applicationsQuery("g1"), List.of(assignment("a1", "sp1")));
        endpoint.lists.put(applicationsQuery("g2"), List.of(assignment("a2", "sp1")));

        List<ConnectorObject> results = searchByApplication(endpoint, FilterBuilder.and(
                FilterBuilder.equalTo(AttributeBuilder.build(Name.NAME, "App-Admins")),
                FilterBuilder.containsAllValues(AttributeBuilder.build(ATTR_APPLICATIONS, "sp1"))));

        assertEquals(Set.of("App-Admins"), names(results));
    }

    @Test
    public void testSearchByOtherApplicationsListsAllGroups() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put("/groups", List.of(group("g1", "Plain"), group("g2", "App-Admins")));
        endpoint.lists.put(applicationsQuery("g2"), List.of(assignment("a2", "sp1")));

        List<ConnectorObject> results = searchByApplication(endpoint, FilterBuilder.or(
                FilterBuilder.equalTo(AttributeBuilder.build(ATTR_APPLICATIONS, "sp1")),
                FilterBuilder.equalTo(AttributeBuilder.build(ATTR_APPLICATIONS, "sp2"))));

        assertEquals(Set.of("App-Admins"), names(results));
    }

    @Test
    public void testSearchByApplicationSkipsDeletedGroupsAndMissingApplications() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(assignedToQuery("sp1"), List.of(assignedTo("gone", "Group")));
        endpoint.missing.add("/servicePrincipals/nope/appRoleAssignedTo");

        assertTrue(searchByApplication(endpoint,
                FilterBuilder.equalTo(AttributeBuilder.build(ATTR_APPLICATIONS, "sp1"))).isEmpty());
        assertTrue(searchByApplication(endpoint,
                FilterBuilder.equalTo(AttributeBuilder.build(ATTR_APPLICATIONS, "nope"))).isEmpty());
    }

    @Test
    public void testOtherQueriesAreNotHandled() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        GroupProcessing processing = new GroupProcessing(endpoint);
        List<ConnectorObject> results = new ArrayList<>();

        assertFalse(processing.executeQueryForGroupByApplication(null, results::add, defaults()));
        assertFalse(processing.executeQueryForGroupByApplication(
                FilterBuilder.equalTo(AttributeBuilder.build(Name.NAME, "G1")), results::add, defaults()));
        assertTrue(endpoint.requests.isEmpty());
    }

    @Test
    public void testUpdateAddsAndRemovesApplicationAssignments() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put("/groups/g1/appRoleAssignments?$select=id,resourceId",
                List.of(assignment("a1", "sp1"), assignment("a2", "sp1"), assignment("a3", "sp2")));

        new GroupProcessing(endpoint).updateGroup(new Uid("g1"), Set.of(AttributeDeltaBuilder.build(ATTR_APPLICATIONS,
                List.of("sp2", "sp3"), List.of("sp1", "sp4"))), defaults());

        assertEquals(List.of(
                "DELETE /v1.0/groups/g1/appRoleAssignments/a1",
                "DELETE /v1.0/groups/g1/appRoleAssignments/a2",
                "POST /v1.0/groups/g1/appRoleAssignments"), endpoint.writes);
        JSONObject body = endpoint.bodies.get(0);
        assertEquals("g1", body.getString("principalId"));
        assertEquals("sp3", body.getString("resourceId"));
        assertEquals("00000000-0000-0000-0000-000000000000", body.getString("appRoleId"));
    }

    private static MSGraphConfiguration fastRetries(int maxRetryCount) {
        MSGraphConfiguration configuration = new MSGraphConfiguration();
        configuration.setPostCreateReadRetryBaseDelayMs(1);
        configuration.setPostCreateReadMaxRetryCount(maxRetryCount);
        return configuration;
    }

    private static final String NEW_GROUP_ASSIGNMENTS = "/v1.0/groups/g1/appRoleAssignments";

    private static GroupProcessing withoutInitialDelay(RecordingGraphEndpoint endpoint) {
        GroupProcessing processing = new GroupProcessing(endpoint);
        processing.newGroupApplicationDelayMs = 0;
        return processing;
    }

    @Test
    public void testNewGroupWaitsTwoSecondsByDefault() {
        assertEquals(2000, new GroupProcessing(new RecordingGraphEndpoint()).newGroupApplicationDelayMs);
    }

    @Test
    public void testNewGroupApplicationsPostedWithoutReadingAssignments() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();

        withoutInitialDelay(endpoint).addApplicationsToNewGroup("g1",
                Arrays.asList("sp1", null, "sp1", " ", "sp2"), fastRetries(5));

        // a new group has no assignments to read; duplicate and blank application ids are skipped
        assertTrue(endpoint.requests.isEmpty());
        assertEquals(List.of("POST " + NEW_GROUP_ASSIGNMENTS, "POST " + NEW_GROUP_ASSIGNMENTS), endpoint.writes);
        assertEquals("sp1", endpoint.bodies.get(0).getString("resourceId"));
        assertEquals("sp2", endpoint.bodies.get(1).getString("resourceId"));
        assertEquals("g1", endpoint.bodies.get(0).getString("principalId"));
        assertEquals("00000000-0000-0000-0000-000000000000", endpoint.bodies.get(0).getString("appRoleId"));
    }

    @Test
    public void testNewGroupApplicationRetriedWhileGroupNotReplicated() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.postsUnknownFor.put(NEW_GROUP_ASSIGNMENTS, 2);

        withoutInitialDelay(endpoint).addApplicationsToNewGroup("g1", List.of("sp1"), fastRetries(5));

        assertEquals(3, endpoint.writes.size());
        assertTrue(endpoint.requests.isEmpty());
    }

    @Test
    public void testNewGroupApplicationGivesUpAfterMaxRetries() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.postsUnknownFor.put(NEW_GROUP_ASSIGNMENTS, 99);

        try {
            withoutInitialDelay(endpoint).addApplicationsToNewGroup("g1", List.of("sp1"), fastRetries(3));
            fail("expected UnknownUidException");
        } catch (UnknownUidException expected) {
            // the group stays unreachable, so the create must fail rather than silently skip the assignment
        }
        assertEquals(3, endpoint.writes.size());
    }

    @Test
    public void testNewGroupApplicationAlreadyAssignedCountsAsDone() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.postsAlreadyExist.add(NEW_GROUP_ASSIGNMENTS);

        withoutInitialDelay(endpoint).addApplicationsToNewGroup("g1", List.of("sp1"), fastRetries(5));

        assertEquals(1, endpoint.writes.size());
    }
}
