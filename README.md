# Connector for Microsoft Graph API

This is midPoint/ConnId connector for Microsoft Graph API. It is meant to manage users in Microsoft cloud applications, such as Entra ID (former Azure AD) and Office365.

See https://wiki.evolveum.com/display/midPoint/Microsoft+Graph+API+Connector


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
