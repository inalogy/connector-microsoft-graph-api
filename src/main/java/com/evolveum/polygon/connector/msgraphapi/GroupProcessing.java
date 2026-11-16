package com.evolveum.polygon.connector.msgraphapi;

import com.evolveum.polygon.connector.msgraphapi.util.ResourceQuery;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.OperationTimeoutException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.AndFilter;
import org.identityconnectors.framework.common.objects.filter.AttributeFilter;
import org.identityconnectors.framework.common.objects.filter.CompositeFilter;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.NotFilter;
import org.identityconnectors.framework.common.objects.filter.OrFilter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GroupProcessing extends ObjectProcessing {

    private final static String GROUPS = "/groups";
    private final static String USERS = "/users";
    private final static String ROLE_ASSIGNMENT = "/roleManagement/directory/roleAssignments";
    private final static String SERVICE_PRINCIPALS = "/servicePrincipals";
    private final static String APP_ROLE_ASSIGNMENTS = "/appRoleAssignments";
    private final static String APP_ROLE_ASSIGNED_TO = "/appRoleAssignedTo";
    // the "default access" app role, used when a group is assigned to an application without a specific role
    private final static String DEFAULT_APP_ROLE_ID = "00000000-0000-0000-0000-000000000000";
    // Wait before the first application assignment of a new group, so that it has usually replicated.
    static final long NEW_GROUP_APPLICATION_DELAY_MS = 2000;

    long newGroupApplicationDelayMs = NEW_GROUP_APPLICATION_DELAY_MS;
    private final static String PRINCIPAL_TYPE_GROUP = "Group";

    private static final String ATTR_ALLOWEXTERNALSENDERS = "allowExternalSenders";
    private static final String ATTR_AUTOSUBSCRIBENEWMEMBERS = "autoSubscribeNewMembers";
    private static final String ATTR_CLASSIFICATION = "classification";
    private static final String ATTR_CREATEDDATETIME = "createdDateTime";
    private static final String ATTR_DESCRIPTION = "description";
    private static final String ATTR_DISPLAYNAME = "displayName";
    private static final String ATTR_GROUPTYPES = "groupTypes";
    private static final String ATTR_MEMBERSHIPRULE = "membershipRule"; // Need Azure Active Directory Premium license for setting
    private static final String ATTR_MEMBERSHIPRULEPROCESSINGSTATE = "membershipRuleProcessingState"; // Need Azure Active Directory Premium license for update for setting
    private static final String ATTR_ID = "id";
    private static final String ATTR_ISSUBSCRIBEDBYMAIL = "isSubscribedByMail";
    private static final String ATTR_MAIL = "mail";
    private static final String ATTR_MAILENABLED = "mailEnabled";
    private static final String ATTR_MAILNICKNAME = "mailNickname";
    private static final String ATTR_ONPREMISESLASTSYNCDATETIME = "onPremisesLastSyncDateTime";
    private static final String ATTR_ONPREMISESSECURITYIDENTIFIER = "onPremisesSecurityIdentifier";
    private static final String ATTR_ONPREMISESSYNCENABLED = "onPremisesSyncEnabled";
    private static final String ATTR_PROXYADDRESSES = "proxyAddresses";
    private static final String ATTR_SECURITYENABLED = "securityEnabled";
    private static final String ATTR_UNSEENCOUNT = "unseenCount";
    private static final String ATTR_VISIBILITY = "visibility";
    private static final String ATTR_ISASSIGNABLETOROLE = "isAssignableToRole";
    private static final String ATTR_MEMBERS = "members";
    private static final String ATTR_OWNERS = "owners";
    private static final String ATTR_MEMBER_OF_ROLE = "memberOfRole";
    // object ids of the enterprise applications (service principals) the group is assigned to
    public static final String ATTR_APPLICATIONS = "applications";
    // their display names, read with the ids
    public static final String ATTR_APPLICATION_NAMES = "applicationNames";


    protected static final Set<String> EXCLUDE_ATTRS_OF_GROUP = Stream.of(
            ATTR_MEMBERS,
            ATTR_OWNERS,
            ATTR_MEMBER_OF_ROLE,
            ATTR_APPLICATIONS,
            ATTR_APPLICATION_NAMES
    ).collect(Collectors.toSet());

    protected static final Set<String> UPDATABLE_MULTIPLE_VALUE_ATTRS_OF_GROUP = Stream.of(
            ATTR_GROUPTYPES
    ).collect(Collectors.toSet());

    public GroupProcessing(GraphEndpoint graphEndpoint) {
        super(graphEndpoint, ICFPostMapper.builder().build());
    }


    public void buildGroupObjectClass(SchemaBuilder schemaBuilder) {
        schemaBuilder.defineObjectClass(objectClassInfo());
    }

    @Override
    protected String type() {
        return ObjectClass.GROUP_NAME;
    }

    @Override
    protected ObjectClassInfo objectClassInfo() {
        ObjectClassInfoBuilder groupObjClassBuilder = new ObjectClassInfoBuilder();

        groupObjClassBuilder.setType(type());
        groupObjClassBuilder.setDescription("Microsoft Entra ID group");

        //required

        //Supports $filter and $orderby
        AttributeInfoBuilder attrDisplayName = new AttributeInfoBuilder(ATTR_DISPLAYNAME);
        attrDisplayName.setRequired(true).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrDisplayName.build());

        AttributeInfoBuilder attrMailEnabled = new AttributeInfoBuilder(ATTR_MAILENABLED);
        attrMailEnabled.setRequired(true).setType(Boolean.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrMailEnabled.build());

        //Supports $filter
        AttributeInfoBuilder attrMailNickname = new AttributeInfoBuilder(ATTR_MAILNICKNAME);
        attrMailNickname.setRequired(true).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrMailNickname.build());

        //$filter
        AttributeInfoBuilder attrSecurityEnabled = new AttributeInfoBuilder(ATTR_SECURITYENABLED);
        attrSecurityEnabled.setRequired(true).setType(Boolean.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrSecurityEnabled.build());


        //optional

        AttributeInfoBuilder attrAllowExternalSenders = new AttributeInfoBuilder(ATTR_ALLOWEXTERNALSENDERS);
        attrAllowExternalSenders.setRequired(false).setType(Boolean.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrAllowExternalSenders.build());

        AttributeInfoBuilder attrAutoSubscribeNewMembers = new AttributeInfoBuilder(ATTR_AUTOSUBSCRIBENEWMEMBERS);
        attrAutoSubscribeNewMembers.setRequired(false).setType(Boolean.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrAutoSubscribeNewMembers.build());

        AttributeInfoBuilder attrClassification = new AttributeInfoBuilder(ATTR_CLASSIFICATION);
        attrClassification.setRequired(false).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrClassification.build());

        AttributeInfoBuilder attrCreatedDateTime = new AttributeInfoBuilder(ATTR_CREATEDDATETIME);
        attrCreatedDateTime.setRequired(false)
                //.setType(Date.class)
                .setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrCreatedDateTime.build());

        AttributeInfoBuilder attrDescription = new AttributeInfoBuilder(ATTR_DESCRIPTION);
        attrDescription.setRequired(false).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrDescription.build());

        //Supports $filter
        AttributeInfoBuilder attrGroupTypes = new AttributeInfoBuilder(ATTR_GROUPTYPES);
        attrGroupTypes.setRequired(false).setType(String.class).setMultiValued(true).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrGroupTypes.build());

        AttributeInfoBuilder attrMembershipRule = new AttributeInfoBuilder(ATTR_MEMBERSHIPRULE);
        attrGroupTypes.setRequired(false).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrMembershipRule.build());

        AttributeInfoBuilder attrMembershipRuleProcessingState = new AttributeInfoBuilder(ATTR_MEMBERSHIPRULEPROCESSINGSTATE);
        attrGroupTypes.setRequired(false).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrMembershipRuleProcessingState.build());

        // Not nullable, Read-only.
        AttributeInfoBuilder attrId = new AttributeInfoBuilder(ATTR_ID);
        attrId.setRequired(false).setType(String.class).setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrId.build());

        AttributeInfoBuilder attrIsSubscribedByMail = new AttributeInfoBuilder(ATTR_ISSUBSCRIBEDBYMAIL);
        attrIsSubscribedByMail.setRequired(false).setType(Boolean.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrIsSubscribedByMail.build());

        //Read-only, supports $filter.
        AttributeInfoBuilder attrMail = new AttributeInfoBuilder(ATTR_MAIL);
        attrMail.setRequired(false).setType(String.class).setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrMail.build());

        //Read-only, supports $filter.
        AttributeInfoBuilder attrOnPremisesLastSyncDateTime = new AttributeInfoBuilder(ATTR_ONPREMISESLASTSYNCDATETIME);
        attrOnPremisesLastSyncDateTime.setRequired(false)
                //.setType(Date.class)
                .setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrOnPremisesLastSyncDateTime.build());

        //read-only
        AttributeInfoBuilder attrOnPremisesSecurityIdentifier = new AttributeInfoBuilder(ATTR_ONPREMISESSECURITYIDENTIFIER);
        attrOnPremisesSecurityIdentifier.setRequired(false).setType(String.class).setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrOnPremisesSecurityIdentifier.build());

        //Read-only, Supports $filter.
        AttributeInfoBuilder attrOnPremisesSyncEnabled = new AttributeInfoBuilder(ATTR_ONPREMISESSYNCENABLED);
        attrOnPremisesSyncEnabled.setRequired(false).setType(Boolean.class).setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrOnPremisesSyncEnabled.build());

        //Read-only, Not nullable, Supports $filter, multivalued
        AttributeInfoBuilder attrProxyAddresses = new AttributeInfoBuilder(ATTR_PROXYADDRESSES);
        attrProxyAddresses.setRequired(false).setType(String.class).setMultiValued(true).setCreateable(false).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrProxyAddresses.build());

        AttributeInfoBuilder attrUnseenCount = new AttributeInfoBuilder(ATTR_UNSEENCOUNT);
        attrUnseenCount.setRequired(false).setType(Integer.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrUnseenCount.build());

        AttributeInfoBuilder attrVisibility = new AttributeInfoBuilder(ATTR_VISIBILITY);
        attrVisibility.setRequired(false).setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrVisibility.build());

        AttributeInfoBuilder attrIsAssignableToRole = new AttributeInfoBuilder(ATTR_ISASSIGNABLETOROLE);
        attrIsAssignableToRole.setRequired(false).setType(Boolean.class).setCreateable(true).setUpdateable(false).setReadable(true);
        groupObjClassBuilder.addAttributeInfo(attrIsAssignableToRole.build());

        AttributeInfoBuilder attrMembers = new AttributeInfoBuilder(ATTR_MEMBERS);
        attrMembers.setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true).setMultiValued(true).setReturnedByDefault(false);
        groupObjClassBuilder.addAttributeInfo(attrMembers.build());

        AttributeInfoBuilder attrOwners = new AttributeInfoBuilder(ATTR_OWNERS);
        attrOwners.setType(String.class).setCreateable(true).setUpdateable(true).setReadable(true).setMultiValued(true).setReturnedByDefault(false);
        groupObjClassBuilder.addAttributeInfo(attrOwners.build());

        // directory extensions
        for (AttributeInfo extAttr : directoryExtensionSchema()) {
            groupObjClassBuilder.addAttributeInfo(extAttr);
        }

        AttributeInfoBuilder attrRoleMembers = new AttributeInfoBuilder(ATTR_MEMBER_OF_ROLE);
        attrRoleMembers.setRequired(false).setType(String.class).setMultiValued(true)
                .setCreateable(false).setUpdateable(false).setReadable(true)
                .setReturnedByDefault(false);
        groupObjClassBuilder.addAttributeInfo(attrRoleMembers.build());

        // one extra request per group, so only on request
        AttributeInfoBuilder attrApplications = new AttributeInfoBuilder(ATTR_APPLICATIONS);
        attrApplications.setRequired(false).setType(String.class).setMultiValued(true)
                .setCreateable(true).setUpdateable(true).setReadable(true)
                .setReturnedByDefault(false);
        groupObjClassBuilder.addAttributeInfo(attrApplications.build());

        AttributeInfoBuilder attrApplicationNames = new AttributeInfoBuilder(ATTR_APPLICATION_NAMES);
        attrApplicationNames.setRequired(false).setType(String.class).setMultiValued(true)
                .setCreateable(false).setUpdateable(false).setReadable(true)
                .setReturnedByDefault(false);
        groupObjClassBuilder.addAttributeInfo(attrApplicationNames.build());

        return groupObjClassBuilder.build();
    }

    private List<AttributeInfo> directoryExtensionSchema() {
        List<AttributeInfo> dirExtSchema = new ArrayList<>();
        if (getConfiguration().getGroupDirectoryExtensions() == null) {
            return dirExtSchema;
        }
        for (String ext : getConfiguration().getGroupDirectoryExtensions()) {
            String[] split = ext.split(";");
            LOG.ok("Adding directory extension {0}", ext);
            if (split.length != 4) {
                LOG.error("Invalid directory extension definition: {0}. Skipping.", ext);
                continue;
            }
            String name = split[0];
            String type = split[1];
            boolean multivalue = false;
            boolean required = false;
            if (split[2].toLowerCase().equals("true")) {
                multivalue = true;
            }
            if (split[3].toLowerCase().equals("true")) {
                required = true;
            }
            if (!name.startsWith("extension_")) {
                LOG.ok("extension prefix not found in name adding it to {0}", name);
                name = "extension_" + name;
            }
            switch (type.toLowerCase()) {
                case "string":
                    LOG.info("Adding string extension {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(String.class).setMultiValued(multivalue).setRequired(required).build());
                    break;
                case "boolean":
                    LOG.info("Adding boolean extension {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(Boolean.class).setMultiValued(false).setRequired(required).build());
                    break;
                case "integer":
                    LOG.info("Adding integer extension {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(Integer.class).setMultiValued(false).setRequired(required).build());
                    break;
                case "binary":
                    LOG.info("Adding binary extension {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(byte[].class).setMultiValued(multivalue).setRequired(required).build());
                    break;
                case "datetime":
                    // also to be handled as string
                    LOG.info("Adding datetime extension, processed as string {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(String.class).setMultiValued(multivalue).setRequired(required).build());
                    break;
                case "reference":
                    LOG.info("Adding reference extension, processed as string {0},{1},{2}", name,
                            multivalue ? "multivalued" : "single-value",
                            required ? "required" : "not required");
                    // also to be handled as string, possible association can be done in midpoint
                    dirExtSchema.add(new AttributeInfoBuilder(name)
                            .setType(String.class).setMultiValued(multivalue).setRequired(required).build());
                    break;
                default:
                    LOG.error("Invalid extension attribute type: {0}. Skipping.", type);
                    throw new InvalidAttributeValueException("Invalid extension attribute type: " + type);
            }
        }
        return dirExtSchema;
    }

    protected Uid createGroup(Set<Attribute> attributes) {
        LOG.info("Start createGroup, attributes: {0}", attributes);
        final GraphEndpoint endpoint = getGraphEndpoint();
        final URIBuilder uriBuilder = endpoint.createURIBuilder();

        AttributesValidator.builder()
                .withNonEmpty(ATTR_DISPLAYNAME, ATTR_MAILENABLED, ATTR_MAILNICKNAME, ATTR_SECURITYENABLED)
                .build().validate(attributes);

        uriBuilder.setPath(GROUPS);
        URI uri = endpoint.getUri(uriBuilder);
        LOG.info("Path: {0}", uri);
        HttpEntityEnclosingRequestBase request = new HttpPost(uri);
        JSONObject jsonObject = buildLayeredAttributeJSON(attributes, EXCLUDE_ATTRS_OF_GROUP);
        String groupDisplayName = jsonObject.getString(getNameAttribute());

        // Tentatively create group with owners or members if present
        // When owners are not present the group is set as unmodifiable
        // For more information refer to https://learn.microsoft.com/en-us/graph/api/group-post-groups?view=graph-rest-1.0&tabs=http
        // This is valid for security group creation
        Attribute members = null;
        Attribute owners = null;
        Attribute applications = null;
        for (Attribute attribute : attributes) {
            switch (attribute.getName()) {
                case ATTR_MEMBERS:
                    members = attribute;
                    break;
                case ATTR_OWNERS:
                    owners = attribute;
                    break;
                case ATTR_APPLICATIONS:
                    applications = attribute;
                    break;
            }
        }
        jsonObject = addIfExistsGroupAccountsToGroup(jsonObject, members, true);
        jsonObject = addIfExistsGroupAccountsToGroup(jsonObject, owners, false);


        // For historical reason, Groups are allowed to duplicate displayName (Reference: https://morgansimonsen.com/2016/06/28/azure-ad-allows-duplicate-group-names/).
        // However, Microsoft strives to avoid group created with duplicated names. For example, duplicate Group creation from the UI will result in an error.
        // Unfortunately, Microsoft Graph v1.0 does not perform duplicate name checking, so we have to implement here.
        //
        // Note: As Group creation is eventual consistency, the existence check here does not work well for
        // freshly created Groups and will result in the creation of duplicate Groups.
        // This is unavoidable due to the system design of Azure AD.
        // (Reference: https://github.com/MicrosoftDocs/azure-docs/issues/94121#issuecomment-1191792188)
        if (isExist(groupDisplayName)) {
            throw new AlreadyExistsException("Another object with the same value for property displayName already exists");
        }

        JSONObject jsonAnswer = endpoint.callRequest(request, jsonObject, true);

        String newUid = jsonAnswer.getString("id");
        LOG.info("The new Uid is {0} ", newUid);

        // It is necessary to wait until the group can be reliably found by its displayName,
        // i.e., until the synchronization on the Entra ID is fully completed.
        // Without this wait, there is a risk that connector will create the group multiple times
        // with the same displayName, due to temporary inconsistency.
        groupSyncFinishedCheck(groupDisplayName, endpoint.getConfiguration());

        // Application assignments need the group id, so they can only follow the creation.
        if (applications != null && applications.getValue() != null) {
            addApplicationsToNewGroup(newUid, applications.getValue(), endpoint.getConfiguration());
        }

        return new Uid(newUid);
    }

    /**
     * Assigns a freshly created group to applications.
     * <p>
     * Entra replicates a new group gradually: it can already be found by its displayName while requests
     * addressing it by id still fail with 404 (or 400 "Invalid object identifier"), both reported as
     * {@link UnknownUidException}. A new group has no application assignments yet, so the current ones are
     * not read; each assignment is posted directly after an initial delay, and a failed post is retried
     * with the post-create backoff. An assignment that turns out to exist already (a retried post whose
     * first attempt went through) counts as done.
     */
    void addApplicationsToNewGroup(String groupId, List<Object> applications, MSGraphConfiguration configuration) {
        Set<String> applicationIds = new LinkedHashSet<>();
        for (Object value : applications) {
            if (value != null && StringUtil.isNotBlank(value.toString())) {
                applicationIds.add(value.toString());
            }
        }
        if (applicationIds.isEmpty()) {
            return;
        }

        sleep(newGroupApplicationDelayMs);
        for (String applicationId : applicationIds) {
            postApplicationAssignmentWithRetry(groupId, applicationId, configuration);
        }
    }

    private void postApplicationAssignmentWithRetry(String groupId, String applicationId, MSGraphConfiguration configuration) {
        int retryCount = 0;
        while (true) {
            try {
                postApplicationAssignment(groupId, applicationId);
                return;
            } catch (AlreadyExistsException e) {
                LOG.info("Group {0} is already assigned to application {1}", groupId, applicationId);
                return;
            } catch (UnknownUidException e) {
                retryCount++;
                if (retryCount >= configuration.getPostCreateReadMaxRetryCount()) {
                    throw e;
                }
                long sleepTime = configuration.getPostCreateReadRetryBaseDelayMs() * (1L << (retryCount - 1));
                LOG.warn("New group {0} not replicated yet, assigning it to application {1} failed ({2}), retry {3} after {4} ms",
                        groupId, applicationId, e.getMessage(), retryCount, sleepTime);
                sleep(sleepTime);
            }
        }
    }

    private void postApplicationAssignment(String groupId, String applicationId) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        LOG.info("Assigning group {0} to application {1}", groupId, applicationId);
        JSONObject assignment = new JSONObject()
                .put("principalId", groupId)
                .put("resourceId", applicationId)
                .put("appRoleId", DEFAULT_APP_ROLE_ID);
        URIBuilder uriBuilder = endpoint.createURIBuilder().setPath(GROUPS + "/" + groupId + APP_ROLE_ASSIGNMENTS);
        endpoint.callRequest(new HttpPost(endpoint.getUri(uriBuilder)), assignment, false);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationTimeoutException("Interrupted while waiting for a new group to replicate");
        }
    }

    protected Set<AttributeDelta> updateGroup(Uid uid, Set<AttributeDelta> attrsDelta, OperationOptions options) {
        LOG.info("Start updateGroup, Uid: {0}, attrsDelta: {1}", uid, attrsDelta);
        final GraphEndpoint endpoint = getGraphEndpoint();

        List<String> oldSelectors = new ArrayList<>();
        AttributeDelta members = null;
        AttributeDelta owners = null;
        AttributeDelta applications = null;
        for (AttributeDelta delta : attrsDelta) {
            if (UPDATABLE_MULTIPLE_VALUE_ATTRS_OF_GROUP.contains(delta.getName())) {
                oldSelectors.add(delta.getName());
                continue;
            }
            if (delta.getName().startsWith("extension_")) {
                if (delta.getValuesToRemove() != null || delta.getValuesToAdd() != null) {
                    oldSelectors.add(delta.getName());
                    continue;
                }
            }
            switch (delta.getName()) {
                case ATTR_MEMBERS:
                    members = delta;
                    break;
                case ATTR_OWNERS:
                    owners = delta;
                    break;
                case ATTR_APPLICATIONS:
                    applications = delta;
                    break;
            }
        }

        // When updating multiple value of the group entity, we need to fetch the current JSON array and merge it with requested delta
        // since Microsoft Graph API doesn't provide a way to patch the JSON array
        JSONObject oldJson = null;
        if (!oldSelectors.isEmpty()) {
            final String select = "$select=" + String.join(",", oldSelectors);

            oldJson = endpoint.executeGetRequest(GROUPS + "/" + uid.getUidValue() + "/", select, options);

            // Remove unrelated keys
            for (String key : oldJson.keySet()) {
                if (!UPDATABLE_MULTIPLE_VALUE_ATTRS_OF_GROUP.contains(key)) {
                    oldJson.remove(key);
                }
            }
        }

        // Update group resource
        final URIBuilder uriBuilder = endpoint.createURIBuilder();
        uriBuilder.setPath(GROUPS + "/" + uid.getUidValue());
        URI uri = endpoint.getUri(uriBuilder);
        LOG.info("Path: {0}", uri);
        HttpEntityEnclosingRequestBase request = new HttpPatch(uri);
        List<JSONObject> attributeList = buildLayeredAttribute(oldJson, attrsDelta, EXCLUDE_ATTRS_OF_GROUP, Collections.emptySet());
        endpoint.callRequestNoContentNoJson(request, attributeList);

        // Update other resources if necessary
        addOrRemoveMember(uid, members, GROUPS);
        addOrRemoveOwner(uid, owners, GROUPS);
        if (applications != null) {
            addOrRemoveApplications(uid.getUidValue(), applications.getValuesToAdd(), applications.getValuesToRemove());
        }

        return null;
    }

    /**
     * Assigns the group to the applications in {@code toAdd} and removes its assignments to the
     * applications in {@code toRemove}. Values are service principal object ids. Adding an application
     * the group is already assigned to, or removing one it is not assigned to, changes nothing.
     */
    private void addOrRemoveApplications(String groupId, List<Object> toAdd, List<Object> toRemove) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String assignmentsPath = GROUPS + "/" + groupId + APP_ROLE_ASSIGNMENTS;

        // application id -> ids of the group's assignments to it (one per app role)
        Map<String, List<String>> current = new LinkedHashMap<>();
        JSONArray assignments = endpoint.executeListRequest(assignmentsPath, "$select=id,resourceId", null, true);
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject assignment = assignments.getJSONObject(i);
            current.computeIfAbsent(assignment.optString("resourceId"), k -> new ArrayList<>())
                    .add(assignment.optString(ATTR_ID));
        }

        if (toRemove != null) {
            for (Object value : toRemove) {
                if (value == null) {
                    continue;
                }
                for (String assignmentId : current.getOrDefault(value.toString(), Collections.emptyList())) {
                    LOG.info("Removing assignment {0} of group {1} to application {2}", assignmentId, groupId, value);
                    URIBuilder uriBuilder = endpoint.createURIBuilder().setPath(assignmentsPath + "/" + assignmentId);
                    endpoint.callRequest(new HttpDelete(endpoint.getUri(uriBuilder)), false);
                }
            }
        }

        if (toAdd != null) {
            for (Object value : toAdd) {
                if (value == null || current.containsKey(value.toString())) {
                    continue;
                }
                postApplicationAssignment(groupId, value.toString());
            }
        }
    }

    private void groupSyncFinishedCheck(String displayName, MSGraphConfiguration configuration) {
        int getGroupByNameRetryCount = 0;
        do {
            if (isExist(displayName)) {
                LOG.ok("Group with displayName {0} FOUND in createOp, retry count: {1}", displayName, getGroupByNameRetryCount);
                return;
            } else {
                getGroupByNameRetryCount++;
                try {
                    long sleepTime = configuration.getPostCreateReadRetryBaseDelayMs() * (1L << (getGroupByNameRetryCount - 1));
                    LOG.warn("Group with displayName {0} NOT found in createOp at loop iteration: {1}. Retrying after {2} ms", displayName, getGroupByNameRetryCount - 1, sleepTime);
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } while (getGroupByNameRetryCount < configuration.getPostCreateReadMaxRetryCount());

        throw new OperationTimeoutException("Maximum retry attempts '" + configuration.getPostCreateReadMaxRetryCount() + "' were exceeded!. Couldn't find freshly created group in createOp.");
    }

    protected boolean isExist(String displayName) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String query = new StringBuilder()
                .append("$filter=")
                .append(getNameAttribute())
                .append(" eq '")
                .append(displayName.replace("'", "''"))
                .append("'")
                .append("&$select=")
                .append(getNameAttribute())
                .append("&$top=1")
                .toString();
        // Use "paging = false" with customQuery contains "$top=1" here since we want to check its existence only
        final JSONArray groups = endpoint.executeListRequest(GROUPS, query, null, false);

        if (groups.isEmpty()) {
            return false;
        }
        String s = groups.getJSONObject(0).getString(getNameAttribute());
        return displayName.equalsIgnoreCase(s);
    }

    protected void delete(Uid uid) {
        if (uid == null) {
            throw new InvalidAttributeValueException("uid not provided");
        }
        HttpDelete request;
        URI uri = null;

        final GraphEndpoint endpoint = getGraphEndpoint();
        final URIBuilder uriBuilder = endpoint.createURIBuilder();
        uriBuilder.setPath(GROUPS + "/" + uid.getUidValue());
        uri = endpoint.getUri(uriBuilder);
        LOG.info("Path: {0}", uri);
        request = new HttpDelete(uri);
        if (endpoint.callRequest(request, false) == null) {
            LOG.info("Deleted group with Uid {0}", uid.getUidValue());
        }

    }

    protected void addOrRemoveMember(Uid uid, AttributeDelta attrDelta, String path) {
        if (attrDelta == null) {
            return;
        }

        LOG.info("addOrRemoveMember {0} , {1} , {2}", uid, attrDelta, path);
        StringBuilder sbPath = new StringBuilder();
        sbPath.append(path).append("/").append(uid.getUidValue()).append("/" + ATTR_MEMBERS);

        LOG.info("path: {0}", sbPath);

        List<Object> removeValues = attrDelta.getValuesToRemove();
        groupProcessRemove(sbPath, removeValues);

        List<Object> addValues = attrDelta.getValuesToAdd();
        if (addValues != null && !addValues.isEmpty()) {
            sbPath.append("/").append("$ref");
            for (Object addValue : addValues) {
                if (addValue != null) {

                    JSONObject json = new JSONObject();
                    String userID = (String) addValue;

                    String addToJson = "https://graph.microsoft.com/v1.0/directoryObjects/" + userID;
                    //POST https://graph.microsoft.com/v1.0/groups/{id}/members/$ref
                    //json.put(ATTR_ID, userID);
                    json.put("@odata.id", addToJson);
                    LOG.ok("json: {0}", json);
                    postRequestNoContent(sbPath.toString(), json);
                }
            }
        }
    }

    public void addOrRemoveOwner(Uid uid, AttributeDelta attrDelta, String path) {
        if (attrDelta == null) {
            return;
        }

        LOG.info("add owner to group or remove ");
        StringBuilder sbPath = new StringBuilder();
        sbPath.append(path).append("/").append(uid.getUidValue()).append("/" + ATTR_OWNERS);


        List<Object> addValues = attrDelta.getValuesToAdd();
        List<Object> removeValues = attrDelta.getValuesToRemove();

        if (addValues != null && !addValues.isEmpty()) {
            sbPath.append("/").append("$ref");
            for (Object addValue : addValues) {
                if (addValue != null) {

                    JSONObject json = new JSONObject();
                    String userID = (String) addValue;
                    //"@odata.id": "https://graph.microsoft.com/v1.0/users/{id}"
                    String addToJson = "https://graph.microsoft.com/v1.0/users/" + userID;
                    //POST https://graph.microsoft.com/v1.0/groups/{id}/owners/$ref
                    //json.put(ATTR_ID, userID);
                    json.put("@odata.id", addToJson);
                    LOG.ok("json: {0}", json);
                    postRequestNoContent(sbPath.toString(), json);
                }
            }
        }
        LOG.info("sbPath : {0} ; removeValues {1}", sbPath, removeValues);
        groupProcessRemove(sbPath, removeValues);
    }

    private void postRequestNoContent(String path, JSONObject json) {
        LOG.info("path: {0} , json {1}, ", path, json);
        final GraphEndpoint endpoint = getGraphEndpoint();
        final URIBuilder uriBuilder = endpoint.createURIBuilder().setPath(path);
        final URI uri = endpoint.getUri(uriBuilder);
        LOG.info("uri {0}", uri);


        HttpEntityEnclosingRequestBase request;
        LOG.info("HttpEntityEnclosingRequestBase request");

        request = new HttpPost(uri);
        LOG.info("create true - HTTP POST {0}", uri);
        endpoint.callRequestNoContent(request, null, json);
    }


    private void groupProcessRemove(StringBuilder sbPath, List<Object> removeValues) {
        if (removeValues != null && !removeValues.isEmpty()) {
            for (Object removeValue : removeValues) {
                if (removeValue != null) {

                    String userID = (String) removeValue;
                    LOG.info("executeDeleteOperation userId: {0} , sbPath: {1} ", userID, sbPath);
                    executeDeleteOperation(new Uid(userID), sbPath.toString());
                }
            }
        }
    }

    private void executeDeleteOperation(Uid uid, String path) {
        LOG.info("Delete object, Uid: {0}, Path: {1}", uid, path);

        final GraphEndpoint endpoint = getGraphEndpoint();
        final URIBuilder uriBuilder = endpoint.createURIBuilder();
        uriBuilder.setPath(path + "/" + uid.getUidValue() + "/$ref");

        LOG.info("Uri for delete: {0}", uriBuilder);

        final URI uri = endpoint.getUri(uriBuilder);
        final HttpRequestBase request = new HttpDelete(uri);
        endpoint.callRequest(request, false);
    }


    public void executeQueryForGroup(ResourceQuery translatedQuery, Boolean fetchSpecific, ResultsHandler handler, OperationOptions options) {
        LOG.ok("Processing executeQuery operation for the objectClass {0}", ObjectClass.GROUP_NAME);
        final GraphEndpoint endpoint = getGraphEndpoint();

        String query = null;
        Boolean fetchAll = false;

        if (translatedQuery != null) {

            query = translatedQuery.toString();

            if (query != null && !query.isEmpty()) {

            } else {
                if (translatedQuery.hasIdOrMembershipExpression()) {
                    query = translatedQuery.getIdOrMembershipExpression();
                } else {

                    fetchAll = true;
                }

            }

        } else {

            fetchAll = true;

        }

        if (!fetchAll) {

            if (fetchSpecific) {
                LOG.info("Fetching object info for object: {0}", query);

                StringBuilder sbPath = new StringBuilder();

                sbPath.append(GROUPS).append("/").append(query);
                JSONObject group = endpoint.executeGetRequest(sbPath.toString(), null, options);
                handleJSONObject(options, group, handler);
            } else {

                if (translatedQuery.hasIdOrMembershipExpression()) {

                    LOG.ok("The constructed filter to be used: {0}", query);
                    endpoint.executeListRequest(translatedQuery.getIdOrMembershipExpression(), query, options, true,
                            createJSONObjectHandler(handler));

                } else {

                    LOG.ok("The constructed filter about to being used: {0}", query);
                    endpoint.executeListRequest(GROUPS, query, options, true, createJSONObjectHandler(handler));
                }
            }

        } else {

            LOG.info("Empty query, returning full list of objects for the {0} object class", ObjectClass.GROUP_NAME);

            endpoint.executeListRequest(GROUPS, null, options, true, createJSONObjectHandler(handler));
        }
    }

    /**
     * Query a group's members, add them to the group's JSON attributes (multivalue)
     *
     * @param group Group to query for (JSON object resulting from previous API call)
     * @return Original JSON, enriched with member information
     */
    private JSONObject saturateGroupMembership(JSONObject group) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String uid = group.getString(ATTR_ID);

        //get list of group members
        final String memberQuery = new StringBuilder()
                .append(GROUPS).append("/").append(uid).append("/")
                .append(ATTR_MEMBERS).toString();
        final JSONArray groupMembers = endpoint.executeListRequest(memberQuery, "$select=id,userPrincipalName", null, true);
        group.put(ATTR_MEMBERS, getJSONArray(groupMembers, "id"));

        return group;
    }

    /**
     * Query a group's owners, add them to the group's JSON attributes (multivalue)
     *
     * @param group Group to query for (JSON object resulting from previous API call)
     * @return Original JSON, enriched with owner information
     */
    private JSONObject saturateGroupOwnership(JSONObject group) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String uid = group.getString(ATTR_ID);

        //get list of group owners
        final String ownerQuery = new StringBuilder()
                .append(GROUPS).append("/").append(uid).append("/")
                .append(ATTR_OWNERS).toString();
        final JSONArray groupOwners = endpoint.executeListRequest(ownerQuery, "$select=id,userPrincipalName", null, true);
        group.put(ATTR_OWNERS, getJSONArray(groupOwners, "id"));

        return group;
    }

    /**
     * Query a group's roles, add them to the group's JSON attributes (multivalue)
     *
     * @param group Group to query for (JSON object resulting from previous API call)
     * @return Original JSON, enriched with roles information
     */
    private JSONObject saturateGroupRoleMembership(JSONObject group) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String uid = group.getString(ATTR_ID);

        LOG.info("[GET] - saturateRoleMembership(), for group with UID: {0}", uid);

        final String customQuery = "$expand=roleDefinition&$select=roleDefinitionId&$filter=principalId eq '" + uid + "'";
        final JSONArray groupMembership = endpoint.executeListRequest(ROLE_ASSIGNMENT, customQuery, null, true);
        group.put(ATTR_MEMBER_OF_ROLE, getJSONArray(groupMembership, "roleDefinitionId"));
        return group;
    }

    /**
     * Query the applications a group is assigned to, add their ids and display names to the group's JSON
     * attributes (multivalue). A group holding several app roles of the same application lists it once.
     *
     * @param group Group to query for (JSON object resulting from previous API call)
     * @return Original JSON, enriched with application information
     */
    private JSONObject saturateGroupApplications(JSONObject group) {
        final GraphEndpoint endpoint = getGraphEndpoint();
        final String uid = group.getString(ATTR_ID);

        final JSONArray assignments = endpoint.executeListRequest(
                GROUPS + "/" + uid + APP_ROLE_ASSIGNMENTS, "$select=resourceId,resourceDisplayName", null, true);
        Set<String> applications = new LinkedHashSet<>();
        Set<String> applicationNames = new LinkedHashSet<>();
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject assignment = assignments.getJSONObject(i);
            String resourceId = assignment.optString("resourceId");
            if (StringUtil.isNotBlank(resourceId) && applications.add(resourceId)) {
                String resourceName = assignment.optString("resourceDisplayName");
                if (StringUtil.isNotBlank(resourceName)) {
                    applicationNames.add(resourceName);
                }
            }
        }
        group.put(ATTR_APPLICATIONS, new JSONArray(applications));
        group.put(ATTR_APPLICATION_NAMES, new JSONArray(applicationNames));
        return group;
    }

    /**
     * Graph cannot filter groups by their application assignments, so a query on {@link #ATTR_APPLICATIONS}
     * is answered here instead of being translated to a Graph filter.
     * <p>
     * When the query names one application (an equality, possibly inside an AND), only that application's
     * assigned groups are read. Any other query on applications reads all groups. Either way the whole query is
     * then evaluated on each group, with its applications loaded.
     *
     * @return false when the query does not concern applications and has to be translated as usual
     */
    public boolean executeQueryForGroupByApplication(Filter query, ResultsHandler handler, OperationOptions options) {
        if (query == null || !referencesApplications(query)) {
            return false;
        }
        final GraphEndpoint endpoint = getGraphEndpoint();
        final ResultsHandler filtering = connectorObject -> !matches(query, connectorObject) || handler.handle(connectorObject);

        final String applicationId = applicationIdOf(query);
        if (applicationId == null) {
            LOG.warn("Filter {0} on {1} names no single application, evaluating it on all groups", query, ATTR_APPLICATIONS);
            endpoint.executeListRequest(GROUPS, null, options, true,
                    (opts, group) -> handleJSONObject(opts, saturateGroupApplications(group), filtering));
            return true;
        }

        final JSONArray assignments;
        try {
            assignments = endpoint.executeListRequest(SERVICE_PRINCIPALS + "/" + applicationId + APP_ROLE_ASSIGNED_TO,
                    "$select=principalId,principalType", null, true);
        } catch (UnknownUidException e) {
            LOG.info("Application {0} does not exist, no group is assigned to it", applicationId);
            return true;
        }

        Set<String> groupIds = new LinkedHashSet<>();
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject assignment = assignments.getJSONObject(i);
            if (PRINCIPAL_TYPE_GROUP.equals(assignment.optString("principalType"))) {
                groupIds.add(assignment.optString("principalId"));
            }
        }

        for (String groupId : groupIds) {
            final JSONObject group;
            try {
                group = endpoint.executeGetRequest(GROUPS + "/" + groupId, null, options);
            } catch (UnknownUidException e) {
                LOG.info("Group {0} assigned to application {1} no longer exists, skipping it", groupId, applicationId);
                continue;
            }
            if (!handleJSONObject(options, saturateGroupApplications(group), filtering)) {
                return true;
            }
        }
        return true;
    }

    /**
     * Evaluates the filter on a group. An equality on {@link #ATTR_APPLICATIONS} means the group is assigned to
     * the application, as midPoint means it for a multivalued attribute; ConnId's own equality would require the
     * group to be assigned to that application alone.
     */
    private static boolean matches(Filter filter, ConnectorObject group) {
        if (filter instanceof AndFilter) {
            return matches(((AndFilter) filter).getLeft(), group) && matches(((AndFilter) filter).getRight(), group);
        }
        if (filter instanceof OrFilter) {
            return matches(((OrFilter) filter).getLeft(), group) || matches(((OrFilter) filter).getRight(), group);
        }
        if (filter instanceof NotFilter) {
            return !matches(((NotFilter) filter).getFilter(), group);
        }
        if ((filter instanceof EqualsFilter || filter instanceof ContainsAllValuesFilter)
                && ATTR_APPLICATIONS.equals(((AttributeFilter) filter).getName())) {
            Attribute applications = group.getAttributeByName(ATTR_APPLICATIONS);
            List<Object> required = ((AttributeFilter) filter).getAttribute().getValue();
            return applications != null && applications.getValue() != null && required != null
                    && applications.getValue().containsAll(required);
        }
        return filter.accept(group);
    }

    private static boolean referencesApplications(Filter filter) {
        if (filter instanceof AttributeFilter) {
            return ATTR_APPLICATIONS.equals(((AttributeFilter) filter).getName());
        }
        if (filter instanceof CompositeFilter) {
            return referencesApplications(((CompositeFilter) filter).getLeft())
                    || referencesApplications(((CompositeFilter) filter).getRight());
        }
        if (filter instanceof NotFilter) {
            return referencesApplications(((NotFilter) filter).getFilter());
        }
        return false;
    }

    /**
     * The single application id the query requires, or null when the query does not require one.
     */
    private static String applicationIdOf(Filter filter) {
        if (filter instanceof EqualsFilter || filter instanceof ContainsAllValuesFilter) {
            Attribute attr = ((AttributeFilter) filter).getAttribute();
            if (ATTR_APPLICATIONS.equals(attr.getName()) && attr.getValue() != null && attr.getValue().size() == 1
                    && attr.getValue().get(0) != null) {
                return AttributeUtil.getAsStringValue(attr);
            }
            return null;
        }
        if (filter instanceof AndFilter) {
            String left = applicationIdOf(((AndFilter) filter).getLeft());
            return left != null ? left : applicationIdOf(((AndFilter) filter).getRight());
        }
        return null;
    }

    /**
     * Adds group accounts to the group JSON Object. Decides upon members or owners based on @param isMembers.
     *
     * @param group     Original group object to extend with owners or members account ids
     * @param accounts  Attribute of account ids
     * @param isMembers Flag to determine whether to add members or owners
     * @return JSONObject of group with respective owners or members key.
     */
    private JSONObject addIfExistsGroupAccountsToGroup(JSONObject group, Attribute accounts, boolean isMembers) {
        if (accounts == null) {
            return group;
        }
        final GraphEndpoint endpoint = getGraphEndpoint();

        JSONArray json = new JSONArray();
        accounts.getValue().forEach(it -> {
            final String ownerQuery = USERS + "/" + it.toString();
            try {
                json.put(endpoint.createURIBuilder().setPath(ownerQuery).build().toString());
            } catch (Exception e) {
                // Unable to create an URI from member
            }
        });
        String key = isMembers ? "members@odata.bind" : "owners@odata.bind";

        group.put(key, json);
        return group;
    }

    @Override
    protected boolean handleJSONObject(OperationOptions options, JSONObject group, ResultsHandler handler) {
        LOG.ok("handleJSONObject");
        if (shouldSaturate(options, ObjectClass.GROUP_NAME, ATTR_MEMBERS)) {
            group = saturateGroupMembership(group);
        }

        if (shouldSaturate(options, ObjectClass.GROUP_NAME, ATTR_OWNERS)) {
            group = saturateGroupOwnership(group);
        }

        if (shouldSaturate(options, ObjectClass.GROUP_NAME, ATTR_MEMBER_OF_ROLE)) {
            group = saturateGroupRoleMembership(group);
        }

        // a search by application has loaded them already
        if (!group.has(ATTR_APPLICATIONS) && (shouldSaturate(options, ObjectClass.GROUP_NAME, ATTR_APPLICATIONS)
                || shouldSaturate(options, ObjectClass.GROUP_NAME, ATTR_APPLICATION_NAMES))) {
            group = saturateGroupApplications(group);
        }

        ConnectorObjectBuilder builder = convertGroupJSONObjectToConnectorObject(group);

        incompleteIfNecessary(options, ObjectClass.GROUP_NAME, ATTR_MEMBERS, builder);
        incompleteIfNecessary(options, ObjectClass.GROUP_NAME, ATTR_OWNERS, builder);
        incompleteIfNecessary(options, ObjectClass.GROUP_NAME, ATTR_MEMBER_OF_ROLE, builder);
        if (!group.has(ATTR_APPLICATIONS)) {
            incompleteIfNecessary(options, ObjectClass.GROUP_NAME, ATTR_APPLICATIONS, builder);
            incompleteIfNecessary(options, ObjectClass.GROUP_NAME, ATTR_APPLICATION_NAMES, builder);
        }

        final ConnectorObject connectorObject = builder.build();
        LOG.ok("handleJSONObject, group: {0}, \n\tconnectorObject: {1}", group.get("id"), connectorObject);
        return handler.handle(connectorObject);
    }

    private ConnectorObjectBuilder convertGroupJSONObjectToConnectorObject(JSONObject group) {
        LOG.ok("convertGroupJSONObjectToConnectorObject execution");
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(ObjectClass.GROUP);

        getUIDIfExists(group, ATTR_ID, builder);
        getNAMEIfExists(group, ATTR_DISPLAYNAME, builder);

        getIfExists(group, ATTR_DISPLAYNAME, String.class, builder);
        getIfExists(group, ATTR_DESCRIPTION, String.class, builder);
        getMultiIfExists(group, ATTR_GROUPTYPES, builder);
        getIfExists(group, ATTR_MEMBERSHIPRULE, String.class, builder);
        getIfExists(group, ATTR_MEMBERSHIPRULEPROCESSINGSTATE, String.class, builder);
        getIfExists(group, ATTR_MAIL, String.class, builder);
        getIfExists(group, ATTR_MAILENABLED, Boolean.class, builder);
        getIfExists(group, ATTR_MAILNICKNAME, String.class, builder);
        getIfExists(group, ATTR_ONPREMISESLASTSYNCDATETIME, String.class, builder);
        getIfExists(group, ATTR_ONPREMISESSECURITYIDENTIFIER, String.class, builder);
        getIfExists(group, ATTR_ONPREMISESSYNCENABLED, Boolean.class, builder);
        getMultiIfExists(group, ATTR_PROXYADDRESSES, builder);
        getIfExists(group, ATTR_SECURITYENABLED, Boolean.class, builder);
        getIfExists(group, ATTR_VISIBILITY, String.class, builder);
        getIfExists(group, ATTR_CREATEDDATETIME, String.class, builder);
        getIfExists(group, ATTR_CLASSIFICATION, String.class, builder);
        getIfExists(group, ATTR_ISASSIGNABLETOROLE, Boolean.class, builder);

        getIfExists(group, ATTR_ALLOWEXTERNALSENDERS, Boolean.class, builder);
        getIfExists(group, ATTR_AUTOSUBSCRIBENEWMEMBERS, Boolean.class, builder);
        getIfExists(group, ATTR_ISSUBSCRIBEDBYMAIL, Boolean.class, builder);
        getIfExists(group, ATTR_UNSEENCOUNT, Integer.class, builder);

        getMultiIfExists(group, ATTR_MEMBERS, builder);
        getMultiIfExists(group, ATTR_OWNERS, builder);
        getMultiIfExists(group, ATTR_MEMBER_OF_ROLE, builder);
        getMultiIfExists(group, ATTR_APPLICATIONS, builder);
        getMultiIfExists(group, ATTR_APPLICATION_NAMES, builder);

        for (AttributeInfo extAttr : directoryExtensionSchema()) {
            if (extAttr.isMultiValued()) {
                getMultiIfExists(group, extAttr.getName(), builder);
            } else {
                getIfExists(group, extAttr.getName(), extAttr.getType(), builder);
            }
        }

        return builder;
    }

    public String getNameAttribute() {

        return ATTR_DISPLAYNAME;
    }

    public String getUIDAttribute() {

        return ATTR_ID;
    }

}
