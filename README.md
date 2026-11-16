# Connector for Microsoft Graph API

This is midPoint/ConnId connector for Microsoft Graph API. It is meant to manage users in Microsoft cloud applications, such as Entra ID (former Azure AD) and Office365.

See https://wiki.evolveum.com/display/midPoint/Microsoft+Graph+API+Connector

## Entra enterprise applications (this fork)

This fork of the Evolveum connector adds what is needed to manage **access to Entra enterprise applications
through security groups** from midPoint, plus fixes found while testing it against a real tenant. Everything
else behaves as in upstream.

The intended model (see [Example midPoint configuration](#example-midpoint-configuration)):

| Entra object | midPoint object | Direction |
|---|---|---|
| Enterprise application (service principal) | Org under "Enterprise Applications" | Read only. Applications are never created from midPoint. |
| Security group | Role (archetype *Entra Group*) | **Created, updated and deleted from midPoint.** The group is assigned to the applications whose orgs the role belongs to. Role holders become group members. |
| Directory role (`roleDefinitions`) | Role (archetype *Entra Role*) | Read only definitions. Holders are assigned from midPoint, the roles themselves are never created. |
| User | User | Read; accounts gain and lose group memberships and directory role assignments. |

### Capabilities added

**Enterprise applications: object class `servicePrincipal`** (read only)

- Lists the tenant's enterprise applications. Which service principals count as one is set by the configuration
  property `servicePrincipalFilter` (default `tags/any(t:t eq 'WindowsAzureActiveDirectoryIntegratedApp')`, i.e.
  the apps shown under *Enterprise applications* in the portal).
- Attributes include `appId`, `displayName`, `accountEnabled`, `appRoleAssignmentRequired`, `servicePrincipalType`,
  `preferredSingleSignOnMode`, `homepage`, `loginUrl`, `notes`, `tags`, `servicePrincipalNames`, `appRoles`, and
  `assignedGroups` (object ids of the groups assigned to the application).
- Create, update and delete are rejected.
- `enterpriseAppName` is an informational configuration property for midPoint scripts; the connector does not use it.

**Groups: application assignment** (`Group` object class)

- `applications` (multivalued, writable, fetched explicitly): object ids of the enterprise applications the group is
  assigned to. Adding a value posts an app role assignment (`POST /groups/{id}/appRoleAssignments`, default app
  role `00000000-0000-0000-0000-000000000000`), removing one deletes it.
- `applicationNames` (read only): display names of those applications, from the same request.
- Searching groups by `applications = <service principal id>` returns the groups assigned to that application
  (`/servicePrincipals/{id}/appRoleAssignedTo`); Graph itself cannot filter groups this way.
- **Group create with applications:** Entra replicates a new group with a delay, and an immediate app role assignment
  answers 404 or 400 *Invalid object identifier*. The connector waits 2 s after creating the group, then posts each
  assignment and retries with exponential backoff (`postCreateReadRetryBaseDelayMs`, `postCreateReadMaxRetryCount`).
  An assignment that already exists (409) counts as done, so a failed create can be retried safely.

**Directory roles: holders** (`Role` object class, `members`)

- Adding a member creates a tenant-wide role assignment (`POST /roleManagement/directory/roleAssignments`,
  `directoryScopeId: "/"`), removing one deletes it. Removing an assignment that does not exist is a no-op.
- The default user roles (*User*, *Guest User*, *Restricted Guest User*) are implicit: Graph answers 404 when their
  assignments are listed, and rejects explicit assignment with *"Adding/Removing an assignment to the implicit user
  roles is not supported."* The connector reports such a role with no members instead of reporting the role as
  missing. Upstream reported it as missing, which made midPoint mark the role's shadow dead.
- A 404 when listing role assignments is retried only for requests filtered by principal (a principal created moments
  ago may not be replicated yet). Requests filtered by role definition fail fast.

**Error messages**

- Graph's own error message (`error.message`, on one line, at most 500 characters) is appended to the exception
  message after the status and provider code, e.g.
  `HTTP error 400 Bad Request (provider code: Request_BadRequest): Adding/Removing an assignment to the implicit user roles is not supported.`

### Example midPoint configuration

[`objects/`](objects) holds the midPoint 4.10 configuration of the development environment this fork was tested in.
It shows the model above end to end:

| Folder | Objects |
|---|---|
| `resources/` | `Resource_Entra_Test.xml`: schemaHandling for accounts, groups (`entitlement/group`) and directory roles (`entitlement/directoryrole`), associations `ri:groups` and `ri:directoryRoles`, inbound and outbound mappings |
| `archetypes/` | *Entra Test Enterprise Application* (org), *Entra Group* and *Entra Role* (role) |
| `orgs/` | *Enterprise Applications*, the parent org of the application orgs |
| `objectTemplates/` | Naming template, and the Entra Group template that assigns the group metarole |
| `roles/` | `MR:RES:EntraTest:Group` (order 1: the Entra group; order 2: membership of role holders) and `MR:RES:EntraTest:DirectoryRole` (order 2: directory role assignment of role holders) |
| `tasks/` | Imports (run in this order: enterprise applications, groups, directory roles, users) and a recompute of the group roles |

Notes:

- Connection settings use `$(customer.*)` placeholders (client id, client secret, tenant id); they must be resolved
  before upload.
- Inbound membership mappings run only on the import, reconciliation and live sync channels, and skip a membership
  that already has an assignment made in midPoint. Without that, removing a role in midPoint would be re-added in
  the same operation from the membership Entra still reported, and imports would duplicate assignments made in midPoint.
- The directory role metarole is assigned per role, explicitly. It must never be assigned to the default user roles.
- Deleting an Entra Group role in midPoint deletes the Entra group.


## Build with Maven

* download Microsoft Graph API connector source code from github
* build connector with maven: 
```
mvn clean install -Dmaven.test.skip=true
```
* find connector-msgraph-{version}.jar in ```\target``` folder

## Installation

* put connector-msgraph-{version}.jar to ```{midPoint_home}\icf-connectors\``` directory
* run/restart midPoint 
 
## Config

* Import of SSL certificates is needed. Download current DigiCert Global Root G2 and DigiCert Global Root CA certificate in .der format and after that you must import it to midPoint keystore.jceks:
```
keytool -keystore keystore.jceks -storetype jceks -storepass changeit -import -alias nlight -trustcacerts -file {your certificate}.der
```
* create your application in Entra ID (former Azure Active Directory), for more information how to create it please see https://docs.microsoft.com/en-us/microsoft-identity-manager/microsoft-identity-manager-2016-connector-graph.
* add all DELEGATED permissions - see Permissions.
* fill all required Configuration properties in resource (clientId, clientSecret, tenantId) - see also samples.

### Authentication

The connector uses MSAL4J confidential-client authentication against the
tenant-specific authority `https://login.microsoftonline.com/{tenantId}` and
requests the Microsoft Graph application scope
`https://graph.microsoft.com/.default`. One MSAL application is retained for a
connector configuration so that MSAL's token cache can be reused.

### Optional authentication integration test

Authentication integration tests are opt-in and must use a dedicated
non-production tenant. Supply values through an approved secret-injection
mechanism as process environment variables; never put them in the tracked
`src/test/resources/testProperties/propertiesForTest.properties` file.

Required for both modes:

- `MSGRAPH_TEST_CLIENT_ID`
- `MSGRAPH_TEST_TENANT_ID`

Additional client-secret variable:

- `MSGRAPH_TEST_CLIENT_SECRET`

Additional certificate variables:

- `MSGRAPH_TEST_CERTIFICATE_PATH`
- `MSGRAPH_TEST_PRIVATE_KEY_PATH`

Run only after confirming the tenant, least-privilege application permissions,
consent, proxy/TLS requirements, and local secret handling:

```
mvn -Dgroups=authentication-integration -Dtest=AuthenticationIntegrationTest test
```

The test performs authentication and the connector's read-only connection test
once for each configured credential mode. It does not load the tracked tenant
properties file.

## Permissions

This are permissions which you need to add to your Entra ID (former Azure Active Directory) application for midPoint:
### Application Permission
#### Required
* Directory.Read.All
* Directory.ReadWrite.All
* Group.Create
* Group.Read.All
* Group.ReadWrite.All
* Group.Selected
* GroupMember.Read.All
* GroupMember.ReadWrite.All
* PrivilegedAccess.Read.AzureADGroup
* PrivilegedAccess.ReadWrite.AzureADGroup
* User.Invite.All
* User.Read.All
* User.ReadWrite.All
#### Optional: Role Membership Management
* EntitlementManagement.Read.All 
* EntitlementManagement.ReadWrite.All 
* RoleManagement.Read.Directory 
* RoleManagement.ReadWrite.Directory
#### Optional: Sites management
* Site.ReadWrite.All
#### Enterprise applications and application assignment (this fork)
* Application.Read.All (read enterprise applications; Directory.Read.All above also covers it)
* AppRoleAssignment.ReadWrite.All (assign groups to enterprise applications)
#### Directory role holders (this fork)
* RoleManagement.ReadWrite.Directory (create and delete directory role assignments)

## Resource Examples
see [midpoint-samples - Microsoft Graph Connector](https://github.com/Evolveum/midpoint-samples/tree/master/samples/resources/msgraph)

## Sharepoint - Sites Lists schema
### Schema discovery
Schema is provided by loading all non-personal sites from the tenant and its lists. 
The naming convention for schema is SiteName~ListName where columns represent values in the list and its items.

IMPORTANT: It is off by default. It is driven by the property of **discoverSchema**.
When the property is set to TRUE, the connector will automatically go through all the sites based on the configuration. 
If the property **ignorePersonalSites** is set to true, it will go through only non personal sites.
Based on it it will fetch all the lists on each site and expand its columns which will bring the connector to the schema of each list and items in it.
It can be a very time consuming operation considering the size of your SharePoint and number of sites and lists in it.

Be cautious when using the schema discovery and turning it on for large amount of sites.

### Example
**Site name:** Projects
**List name:** Client projects
**Columns:** Name, Key, Shortcut

Schema object: 
- **Projects/Client projects**
  - **Name**: String
  - **Key**: String 
  - **Shortcut**: String

### Limitations
- Personal sites are ignored. It can be enabled by configuring the '**ignorePersonalSites**' property. By default, it is configured to true.
- Midpoint expects __NAME__ attribute to be present in schema. This became a bit problematic during implementation
  - By default, there is no such property within Columns definition as the definition is dynamic
  - Connector expects any of following property names to be present - Name, Title, LinkTitle, DisplayName. These property definitions are case-insensitive.
  - In order to define own propertyNames, alternate connector by defining it in configuration for multivalue property '**expectedPropertyNames**'. Comma separated.
  - As a last resort of naming the item returned by schema definition to fulfill midpoint requirements connector uses ID.
