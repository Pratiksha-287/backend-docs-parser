# Liferay Headless Documentation (0.9.0)

Reads the Liferay OpenAPI catalog and generates timestamped JSON files in `generated/`.
The UTC timestamp sorts chronologically, so the newest file appears first when
sorting filenames in descending order.

## Authentication (OAuth 2.0 client credentials)

In Liferay: Control Panel > Security > OAuth 2 Administration > Add application
- enable the **Client Credentials** grant, pick a **Client Credentials User**, grant scopes.

Run with:

    $env:LIFERAY_BASE_URL=https://your-liferay
    $env:LIFERAY_CLIENT_ID=id-xxxxxxxx
    $env:LIFERAY_CLIENT_SECRET=secret-xxxxxxxx
    ./mvnw spring-boot:run
    curl.exe -X POST http://localhost:8082/api/analyzer/discover/generate

The token is fetched from `/o/oauth2/token`, cached until shortly before it
expires, and re-requested once automatically on HTTP 401.
`LIFERAY_USERNAME` / `LIFERAY_PASSWORD` still work, but only when no client id is set.

## Output format 2.0 (compact)

    api            title, version, servers, global security
    resources[]    apiName, apiVersion, resourceName, operations[]
    parameters{}   de-duplicated parameter definitions ("query.page", "path.accountId", ...)
    schemas{}      every component schema once; other schemas referenced via "$ref": "Name"
    relationships{} grouped by source schema

Operation: `operationId, method, path, summary, description, parameters[] (keys into parameters{}),
requestBody{content[{mediaTypes[], schema}]}, responses{status:{description, content[]}}, security (only if it overrides api.security)`.

To get the full expanded tree of one schema, follow the `$ref` chain in `schemas`,
or call `GET /api/analyzer/discover/schemas/{name}`.

Options: `documentation.output.pretty-print` (default false), `documentation.output.gzip` (default false).
