# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- #41 Support for group directory role assignments
    - Enables querying directory roles assigned to groups.
    - Role assignments are retrieved via `/roleManagement/directory/roleAssignments` and exposed as a multivalued attribute on the group object.

- #42 Support for Entra ID directory extensions
    - Allows use of custom directory extensions in the connector schema.
    - Format extensions to be added to the user schema as "[AppId]_[extensionName];type;multivalued;mandatory",  multivalue and mandatory are boolean values (true/false),. Types can be string, boolean, integer, binary, datetime, reference.  The configuration properties are "User directory extensions schema" and "Group directory extensions schema"
    - The connector automatically prepends the `extension_` prefix when calling the Graph API.

- #38 Schema discovery for SharePoint lists

- #39 Extends possibility of security group creation and its assignment to Roles

### Fixed

- #43 Excluded SharePoint Online (SPO) attributes from `$select` unless explicitly requested via `attributesToGet`.
    - SPO attributes (e.g. `aboutMe`, `mySite`, `birthday`) require a SharePoint Online license; including them in `$select` causes HTTP 400 errors on tenants without SPO.

- #35
    - Fixed license search by UID being ignored and returning all licenses.
    - Fixed “Bad request” errors when searching for a specific license in the `/subscribedSkus` endpoint.

- #37 Repeated queries with exponentially increasing delay
    - Following situations: After user creation, querying their role membership and after group creation, querying its existence by displayName

- #40 Allow creation of editable m365 and security groups
    - Security groups and m365 groups can't be created as editable without specifying owners.

- #45 Microsoft Graph filter rendering for Boolean and null values & other
    - filter rendering for Boolean and null values
    - fixes the TestNG/Surefire setup so unit and integration tests run reliably

### Changed

- #45 Microsoft Graph filter rendering for Boolean and null values & other
    - improves guest invitation creation logic
    - updates the Maven build for Java 17 & changed connector parent (ConnId version)

### Security

- Updated Bouncy Castle library from `bcprov-jdk15on` (legacy, superseded) to `bcprov-jdk18on`.

