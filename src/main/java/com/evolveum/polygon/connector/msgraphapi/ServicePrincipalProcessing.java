package com.evolveum.polygon.connector.msgraphapi;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enterprise applications, i.e. the service principals Entra ID lists under "Enterprise applications".
 * <p>
 * The object class is read-only. {@code assignedGroups} holds the object ids of the groups that have an
 * app role assignment to the application, which lets midPoint associate Entra groups with the
 * application.
 */
public class ServicePrincipalProcessing extends ObjectProcessing {
    public static final String OBJECT_CLASS_NAME = "servicePrincipal";
    public static final ObjectClass OBJECT_CLASS = new ObjectClass(OBJECT_CLASS_NAME);

    private static final String SERVICE_PRINCIPALS = "/servicePrincipals";
    private static final String APP_ROLE_ASSIGNED_TO = "/appRoleAssignedTo";
    private static final String GROUPS = "/groups";
    private static final String APP_ROLE_ASSIGNMENTS = "/appRoleAssignments";

    private static final String PRINCIPAL_TYPE_GROUP = "Group";

    public static final String ATTR_ID = "id";
    public static final String ATTR_APP_ID = "appId";
    public static final String ATTR_DISPLAY_NAME = "displayName";
    public static final String ATTR_ACCOUNT_ENABLED = "accountEnabled";
    public static final String ATTR_APP_ROLE_ASSIGNMENT_REQUIRED = "appRoleAssignmentRequired";
    public static final String ATTR_APP_OWNER_ORGANIZATION_ID = "appOwnerOrganizationId";
    public static final String ATTR_SERVICE_PRINCIPAL_TYPE = "servicePrincipalType";
    public static final String ATTR_PREFERRED_SSO_MODE = "preferredSingleSignOnMode";
    public static final String ATTR_HOMEPAGE = "homepage";
    public static final String ATTR_LOGIN_URL = "loginUrl";
    public static final String ATTR_NOTES = "notes";
    public static final String ATTR_TAGS = "tags";
    public static final String ATTR_SERVICE_PRINCIPAL_NAMES = "servicePrincipalNames";
    // app roles
    public static final String ATTR_APP_ROLES = "appRoles";
    public static final String ATTR_APP_ROLES__ID = ATTR_APP_ROLES + "." + ATTR_ID;
    public static final String ATTR_APP_ROLES__VALUE = ATTR_APP_ROLES + ".value";
    public static final String ATTR_APP_ROLES__DISPLAY_NAME = ATTR_APP_ROLES + "." + ATTR_DISPLAY_NAME;
    // assignments, explicit fetch only
    public static final String ATTR_ASSIGNED_GROUPS = "assignedGroups";

    private static final String SELECTOR_ASSIGNMENT_PRINCIPAL = selector("principalId", "principalType");
    private static final String SELECTOR_ASSIGNMENT_RESOURCE = selector("resourceId");

    public ServicePrincipalProcessing(GraphEndpoint graphEndpoint) {
        super(graphEndpoint, ICFPostMapper.builder().build());
    }

    public void buildServicePrincipalObjectClass(SchemaBuilder schemaBuilder) {
        schemaBuilder.defineObjectClass(objectClassInfo());
    }

    @Override
    protected String type() {
        return OBJECT_CLASS_NAME;
    }

    @Override
    protected ObjectClassInfo objectClassInfo() {
        ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder()
                .setType(type())
                .setDescription("Microsoft Entra ID enterprise application (service principal)");

        builder.addAttributeInfo(readOnly(ATTR_APP_ID, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_DISPLAY_NAME, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_ACCOUNT_ENABLED, Boolean.class).build());
        builder.addAttributeInfo(readOnly(ATTR_APP_ROLE_ASSIGNMENT_REQUIRED, Boolean.class).build());
        builder.addAttributeInfo(readOnly(ATTR_APP_OWNER_ORGANIZATION_ID, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_SERVICE_PRINCIPAL_TYPE, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_PREFERRED_SSO_MODE, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_HOMEPAGE, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_LOGIN_URL, String.class).build());
        builder.addAttributeInfo(readOnly(ATTR_NOTES, String.class).build());

        builder.addAttributeInfo(readOnly(ATTR_TAGS, String.class).setMultiValued(true).build());
        builder.addAttributeInfo(readOnly(ATTR_SERVICE_PRINCIPAL_NAMES, String.class).setMultiValued(true).build());
        builder.addAttributeInfo(readOnly(ATTR_APP_ROLES__ID, String.class).setMultiValued(true).build());
        builder.addAttributeInfo(readOnly(ATTR_APP_ROLES__VALUE, String.class).setMultiValued(true).build());
        builder.addAttributeInfo(readOnly(ATTR_APP_ROLES__DISPLAY_NAME, String.class).setMultiValued(true).build());

        // one extra request per application, so only on request
        builder.addAttributeInfo(readOnly(ATTR_ASSIGNED_GROUPS, String.class)
                .setMultiValued(true)
                .setReturnedByDefault(false)
                .build());

        return builder.build();
    }

    private static AttributeInfoBuilder readOnly(String name, Class<?> type) {
        return new AttributeInfoBuilder(name)
                .setType(type)
                .setRequired(false)
                .setCreateable(false)
                .setUpdateable(false)
                .setReadable(true);
    }

    public void executeQueryForServicePrincipal(Filter query, ResultsHandler handler, OperationOptions options) {
        LOG.info("executeQueryForServicePrincipal() Query: {0}", query);

        if (query == null) {
            list(null, handler, options);
            return;
        }

        if (query instanceof EqualsFilter) {
            final Attribute attr = ((EqualsFilter) query).getAttribute();
            final String attrName = attr.getName();
            final boolean singleValue = attr.getValue() != null && attr.getValue().size() == 1
                    && attr.getValue().get(0) != null;

            if (Uid.NAME.equals(attrName) || ATTR_ID.equals(attrName)) {
                if (!singleValue) {
                    invalidAttributeValue("Uid", query);
                }
                get(AttributeUtil.getAsStringValue(attr), handler, options);
                return;
            }
            if (singleValue && (Name.NAME.equals(attrName) || ATTR_DISPLAY_NAME.equals(attrName))) {
                list(ATTR_DISPLAY_NAME + " eq " + literal(AttributeUtil.getAsStringValue(attr)), handler, options);
                return;
            }
            if (singleValue && ATTR_APP_ID.equals(attrName)) {
                list(ATTR_APP_ID + " eq " + literal(AttributeUtil.getAsStringValue(attr)), handler, options);
                return;
            }
            if (singleValue && ATTR_ASSIGNED_GROUPS.equals(attrName)) {
                listByAssignedGroup(AttributeUtil.getAsStringValue(attr), handler, options);
                return;
            }
        }

        if (query instanceof ContainsAllValuesFilter) {
            final Attribute attr = ((ContainsAllValuesFilter) query).getAttribute();
            if (ATTR_ASSIGNED_GROUPS.equals(attr.getName()) && attr.getValue() != null && attr.getValue().size() == 1
                    && attr.getValue().get(0) != null) {
                listByAssignedGroup(AttributeUtil.getAsStringValue(attr), handler, options);
                return;
            }
        }

        // Anything else is evaluated locally, so midPoint never receives an object the filter excludes.
        LOG.info("Filter {0} is not translated to Graph, evaluating it on the listed applications", query);
        list(null, connectorObject -> !query.accept(connectorObject) || handler.handle(connectorObject), options);
    }

    private void get(String id, ResultsHandler handler, OperationOptions options) {
        // No $select: the default response already holds every attribute of the schema.
        JSONObject json = getGraphEndpoint().executeGetRequest(SERVICE_PRINCIPALS + "/" + id, null, options);
        handleJSONObject(options, json, handler);
    }

    /**
     * Lists the applications admitted by the configured service principal filter, narrowed by
     * {@code additionalFilter} when it is set.
     */
    private void list(String additionalFilter, ResultsHandler handler, OperationOptions options) {
        String filter = combine(getConfiguration().getServicePrincipalFilter(), additionalFilter);
        // Lambda operators such as tags/any(...) are advanced queries and need $count=true.
        String customQuery = filter == null ? null : "$filter=" + filter + "&$count=true";

        Set<String> seen = new LinkedHashSet<>();
        getGraphEndpoint().executeListRequest(SERVICE_PRINCIPALS, customQuery, options, true,
                (opts, json) -> !seen.add(json.optString(ATTR_ID)) || handleJSONObject(opts, json, handler));
    }

    /**
     * Applications a group is assigned to, read from the group's side so that a single request
     * answers the question instead of reading the assignments of every application.
     */
    private void listByAssignedGroup(String groupId, ResultsHandler handler, OperationOptions options) {
        final JSONArray assignments;
        try {
            assignments = getGraphEndpoint().executeListRequest(
                    GROUPS + "/" + groupId + APP_ROLE_ASSIGNMENTS, SELECTOR_ASSIGNMENT_RESOURCE, null, true);
        } catch (UnknownUidException e) {
            LOG.info("Group {0} does not exist, it is assigned to no application", groupId);
            return;
        }

        Set<String> resourceIds = new LinkedHashSet<>();
        for (int i = 0; i < assignments.length(); i++) {
            String resourceId = assignments.getJSONObject(i).optString("resourceId");
            if (StringUtil.isNotBlank(resourceId)) {
                resourceIds.add(resourceId);
            }
        }

        // Each application still has to pass the configured filter, exactly as in a full listing.
        boolean[] proceed = {true};
        for (String resourceId : resourceIds) {
            if (!proceed[0]) {
                return;
            }
            list(ATTR_ID + " eq " + literal(resourceId), connectorObject -> {
                proceed[0] = handler.handle(connectorObject);
                return proceed[0];
            }, options);
        }
    }

    /**
     * Object ids of the groups assigned to the application. A group holding several app roles of the
     * same application appears once.
     */
    List<String> getAssignedGroupIds(String servicePrincipalId) {
        JSONArray assignments = getGraphEndpoint().executeListRequest(
                SERVICE_PRINCIPALS + "/" + servicePrincipalId + APP_ROLE_ASSIGNED_TO,
                SELECTOR_ASSIGNMENT_PRINCIPAL, null, true);

        Set<String> groupIds = new LinkedHashSet<>();
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject assignment = assignments.getJSONObject(i);
            String principalId = assignment.optString("principalId");
            if (PRINCIPAL_TYPE_GROUP.equals(assignment.optString("principalType")) && StringUtil.isNotBlank(principalId)) {
                groupIds.add(principalId);
            }
        }
        return List.copyOf(groupIds);
    }

    @Override
    protected boolean handleJSONObject(OperationOptions options, JSONObject json, ResultsHandler handler) {
        if (shouldSaturate(options, OBJECT_CLASS_NAME, ATTR_ASSIGNED_GROUPS)) {
            json.put(ATTR_ASSIGNED_GROUPS, new JSONArray(getAssignedGroupIds(json.getString(ATTR_ID))));
        }

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(OBJECT_CLASS);

        String id = getUIDIfExists(json, ATTR_ID, builder);
        // displayName is optional in Graph, the name must not be
        String displayName = json.optString(ATTR_DISPLAY_NAME, null);
        builder.setName(StringUtil.isNotBlank(displayName) ? displayName : id);

        getIfExists(json, ATTR_APP_ID, String.class, builder);
        getIfExists(json, ATTR_DISPLAY_NAME, String.class, builder);
        getIfExists(json, ATTR_ACCOUNT_ENABLED, Boolean.class, builder);
        getIfExists(json, ATTR_APP_ROLE_ASSIGNMENT_REQUIRED, Boolean.class, builder);
        getIfExists(json, ATTR_APP_OWNER_ORGANIZATION_ID, String.class, builder);
        getIfExists(json, ATTR_SERVICE_PRINCIPAL_TYPE, String.class, builder);
        getIfExists(json, ATTR_PREFERRED_SSO_MODE, String.class, builder);
        getIfExists(json, ATTR_HOMEPAGE, String.class, builder);
        getIfExists(json, ATTR_LOGIN_URL, String.class, builder);
        getIfExists(json, ATTR_NOTES, String.class, builder);

        getMultiIfExists(json, ATTR_TAGS, builder);
        getMultiIfExists(json, ATTR_SERVICE_PRINCIPAL_NAMES, builder);
        getFromArrayIfExists(json, ATTR_APP_ROLES, ATTR_ID, String.class, builder);
        getFromArrayIfExists(json, ATTR_APP_ROLES, "value", String.class, builder);
        getFromArrayIfExists(json, ATTR_APP_ROLES, ATTR_DISPLAY_NAME, String.class, builder);

        getMultiIfExists(json, ATTR_ASSIGNED_GROUPS, builder);
        incompleteIfNecessary(options, OBJECT_CLASS_NAME, ATTR_ASSIGNED_GROUPS, builder);

        return handler.handle(builder.build());
    }

    private static String combine(String first, String second) {
        if (StringUtil.isBlank(first)) {
            return StringUtil.isBlank(second) ? null : second;
        }
        if (StringUtil.isBlank(second)) {
            return first;
        }
        return "(" + first + ") and " + second;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public String getNameAttribute() {
        return ATTR_DISPLAY_NAME;
    }

    public String getUIDAttribute() {
        return ATTR_ID;
    }
}
