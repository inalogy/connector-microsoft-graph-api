package com.evolveum.polygon.connector.msgraphapi;

import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.*;

import static org.testng.AssertJUnit.*;

/**
 * Test case for the directory role {@code members} of {@link RoleProcessing}: reading them for roles Graph answers
 * 404 for (the default user roles), and removing members idempotently.
 */
@Test(groups = "unit")
public class RoleMembershipTest {

    private static final String ROLE_ASSIGNMENTS = "/roleManagement/directory/roleAssignments";
    private static final String API = "/v1.0";

    private static class RecordingGraphEndpoint extends MockGraphEndpoint {
        // customQuery -> assignments returned; queries in "missing" answer 404 (UnknownUid)
        private final Map<String, List<JSONObject>> lists = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private final List<String> writes = new ArrayList<>();

        RecordingGraphEndpoint() {
            super(null);
        }

        @Override
        protected JSONArray executeListRequest(String path, String customQuery, OperationOptions options, boolean paging) {
            if (missing.contains(customQuery)) {
                throw new UnknownUidException("HTTP error 404 Not Found (provider code: Request_ResourceNotFound)");
            }
            JSONArray result = new JSONArray();
            lists.getOrDefault(customQuery, Collections.emptyList()).forEach(result::put);
            return result;
        }

        @Override
        protected JSONObject callRequest(HttpRequestBase request, boolean parseResult) {
            writes.add(request.getMethod() + " " + request.getURI().getPath());
            return null;
        }

        @Override
        protected void callRequestNoContent(HttpEntityEnclosingRequestBase request, Set<Attribute> attributes, JSONObject jsonObject) {
            writes.add(request.getMethod() + " " + request.getURI().getPath() + " " + jsonObject.getString("principalId"));
        }
    }

    private static String membersQuery(String roleId) {
        return "$select=principalId&$filter=roleDefinitionId eq '" + roleId + "'";
    }

    private static String assignmentIdQuery(String roleId, String principalId) {
        return "$select=id&$filter=roleDefinitionId eq '" + roleId + "' and principalId eq '" + principalId + "'";
    }

    private static JSONObject role(String id, String displayName) {
        return new JSONObject().put("id", id).put("displayName", displayName).put("isBuiltIn", true);
    }

    private static OperationOptions withMembers() {
        return new OperationOptionsBuilder().setReturnDefaultAttributes(true).setAttributesToGet("members").build();
    }

    private static AttributeDelta removeMembers(String... principalIds) {
        return AttributeDeltaBuilder.build("members", null, Arrays.asList((Object[]) principalIds));
    }

    @Test
    public void testMembersAreRead() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(membersQuery("r1"), List.of(new JSONObject().put("principalId", "u1"),
                new JSONObject().put("principalId", "u2")));

        List<ConnectorObject> results = new ArrayList<>();
        new RoleProcessing(endpoint).handleJSONObject(withMembers(), role("r1", "AI Reader"), results::add);

        assertEquals(1, results.size());
        assertEquals(List.of("u1", "u2"), results.get(0).getAttributeByName("members").getValue());
    }

    @Test
    public void testRoleWhoseAssignmentsAnswer404HasNoMembersInsteadOfBeingUnknown() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.missing.add(membersQuery("user-role"));

        List<ConnectorObject> results = new ArrayList<>();
        new RoleProcessing(endpoint).handleJSONObject(withMembers(), role("user-role", "User"), results::add);

        assertEquals("the role must still be returned, not reported as missing", 1, results.size());
        assertEquals("user-role", results.get(0).getUid().getUidValue());
        Attribute members = results.get(0).getAttributeByName("members");
        assertTrue("no members expected", members == null || members.getValue() == null || members.getValue().isEmpty());
    }

    @Test
    public void testRemovingAHeldRoleDeletesItsAssignment() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.lists.put(assignmentIdQuery("r1", "u1"), List.of(new JSONObject().put("id", "ra1")));

        new RoleProcessing(endpoint).updateRole(new Uid("r1"), Set.of(removeMembers("u1")), null);

        assertEquals(List.of("DELETE " + API + ROLE_ASSIGNMENTS + "/ra1"), endpoint.writes);
    }

    @Test
    public void testRemovingAnAbsentAssignmentIsANoOp() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        // no assignment of r1 for u1

        new RoleProcessing(endpoint).updateRole(new Uid("r1"), Set.of(removeMembers("u1")), null);

        assertTrue("nothing may be deleted, got " + endpoint.writes, endpoint.writes.isEmpty());
    }

    @Test
    public void testRemovingFromARoleWhoseAssignmentsAnswer404IsANoOp() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();
        endpoint.missing.add(assignmentIdQuery("user-role", "u1"));

        new RoleProcessing(endpoint).updateRole(new Uid("user-role"), Set.of(removeMembers("u1")), null);

        assertTrue("nothing may be deleted, got " + endpoint.writes, endpoint.writes.isEmpty());
    }

    @Test
    public void testAddingAMemberPostsATenantWideAssignment() {
        RecordingGraphEndpoint endpoint = new RecordingGraphEndpoint();

        new RoleProcessing(endpoint).updateRole(new Uid("r1"),
                Set.of(AttributeDeltaBuilder.build("members", List.of("u1"), null)), null);

        assertEquals(List.of("POST " + API + ROLE_ASSIGNMENTS + " u1"), endpoint.writes);
    }

    @Test
    public void testOnlyPrincipalFiltered404sAreRetried() throws Exception {
        assertTrue(GraphEndpoint.isRetryableRoleAssignments404(new URI(
                "https://graph.microsoft.com/v1.0" + ROLE_ASSIGNMENTS + "?$select=roleDefinitionId&$filter=principalId%20eq%20'u1'")));
        assertTrue(GraphEndpoint.isRetryableRoleAssignments404(new URI(
                "https://graph.microsoft.com/v1.0" + ROLE_ASSIGNMENTS + "/ra1")));
        assertFalse(GraphEndpoint.isRetryableRoleAssignments404(new URI(
                "https://graph.microsoft.com/v1.0" + ROLE_ASSIGNMENTS + "?$select=principalId&$filter=roleDefinitionId%20eq%20'r1'")));
    }
}
