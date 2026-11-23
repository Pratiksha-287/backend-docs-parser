# API Documentation Generator

Spring Boot service that reads a Swagger UI page (or a raw OpenAPI/Swagger
JSON/YAML file), discovers every API definition it exposes, and produces one
detailed JSON documentation file.

Supported inputs: Swagger 2.0, OpenAPI 3.0 and 3.1.

Output contract: **2.0** (compact). See "Output format" below.

## Run

```bash
./mvnw spring-boot:run
```

The service listens on port `8082` (see `application.properties`).

## Endpoints

### `POST /api/v1/documentation/generate/url`

```json
{
  "sourceUrl": "https://host/path/swagger-ui/index.html",
  "projectName": "My Project",
  "authentication": { "type": "BEARER", "token": "<token>" }
}
```

`authentication` is optional. The bearer token is sent only to the same
origin (scheme, host, port) as `sourceUrl`.

`sourceUrl` may be a Swagger UI page (all configured APIs are discovered via
`configUrl`, `urls` or `url`) or a direct OpenAPI JSON/YAML URL.

### `POST /api/v1/documentation/generate/file`

Multipart form: `file` (`.json`, `.yaml`, `.yml`) and `projectName`.

### `GET /api/v1/documentation/{documentId}/download`

Downloads the generated JSON.

If one discovered API is unavailable (404, 401, parse error, ...) the others
are still documented. The failed ones are listed in `failedApis` in both the
response and the generated JSON. The request only fails if **every** API fails.

Both generate endpoints return:

```json
{ "documentId": "...", "downloadUrl": "/api/v1/documentation/<id>/download",
  "statistics": { "totalEndpoints": 0, "totalSchemas": 0,
                  "totalModules": 0, "totalRelationships": 0 },
  "failedApis": [ { "name": "...", "specificationUrl": "...", "error": "..." } ] }
```

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `documentation.storage.directory` | `./generated` | Where JSON files are saved |
| `documentation.source.max-size-bytes` | `10485760` | Max specification size |
| `documentation.source.connect-timeout-seconds` | `10` | HTTP connect timeout |
| `documentation.source.read-timeout-seconds` | `30` | HTTP read timeout |
| `documentation.parser.resolve-references` | `false` | Resolve `$ref`s while parsing |
| `documentation.output.pretty` | `false` | Pretty-print the JSON (about 30% larger) |
| `documentation.output.include-original` | `false` | Also embed the full parsed specification (roughly doubles the size) |

## Output format (contract 2.0)

Goal: everything a reader needs to understand the API, nothing twice.

```
contractVersion, documentMetadata, statistics
modules[]        name, description, schemas[], endpoints[]
schemas{}        reusable models with fields, constraints and descriptions
securitySchemes{}
relationships[]  from, to, type, via, required, description
workflows[]      name, description, operations[], classification, confidence
parserMessages[]
```

A legend of the notation is written to `documentMetadata.conventions`
(once per file, also for multi-API files).

### What changed compared to 1.0

| Area | 1.0 | 2.0 |
|---|---|---|
| Size | raw swagger-parser objects, `originalSpecification` copy, pretty printed, nulls | compact notation, no copy, single-line, no null / empty values |
| Endpoints | repeated under every tag | stored once under the primary tag |
| Parameters / bodies / responses | raw model (`$ref`, empty fields) | `type` notation (`User`, `User[]`, `integer(int64)`), constraints, media types merged when the schema is identical |
| Responses | generic texts ("OK") repeated | generic reason phrases dropped when a typed body exists |
| Missing summary | `null` | inferred from the operationId / method + path, `descriptionInferred: true` |
| Missing module description | empty | inferred (methods and schemas used), flagged |
| Missing schema description | none | usage sentence ("Request body of ...; returned by ..."), flagged |
| Relationships | only `endpoint -> schema` (`USES_SCHEMA`) with the `$ref` string as evidence | `REQUEST_BODY`, `RESPONSE`, `PARAMETER`, `HAS_ONE`, `HAS_MANY`, `EXTENDS`, `ONE_OF`, `ANY_OF`, each with `via` and a one-sentence `description` |
| Workflows | only explicit OpenAPI links (almost never present in Spring Boot specs) | plus inferred CRUD lifecycle, nested resources and authentication, with confidence |
| Spring specifics | - | `Pageable` style query objects list their `fields`; springfox / springdoc operationId suffixes (`UsingGET_1`, `_1`) are ignored when inferring text |
| Security | raw requirement list | `auth: ["bearerAuth", "oauth2[read,write]"]`, `public: true` for explicit opt-out |

### Illustrative excerpt

```json
{
  "contractVersion": "2.0",
  "modules": [{
    "name": "user-controller",
    "description": "User operations (GET, POST, DELETE); works with User, UserRequest, PageUser.",
    "descriptionInferred": true,
    "schemas": ["UserRequest", "User", "Pageable", "PageUser"],
    "endpoints": [{
      "operationId": "createUser", "method": "POST", "path": "/api/v1/users",
      "summary": "Create user", "descriptionInferred": true,
      "auth": ["bearerAuth"],
      "requestBody": {"required": true, "type": "UserRequest"},
      "responses": {"201": {"type": "User"}, "400": {"description": "Validation failed", "type": "ErrorResponse"}}
    }]
  }],
  "schemas": {
    "User": {"type": "object", "description": "Request body of ...; returned by POST /api/v1/users (201), GET /api/v1/users/{id}",
             "descriptionInferred": true,
             "properties": {"id": {"type": "integer(int64)", "readOnly": true},
                            "email": {"type": "string", "maxLength": 120, "required": true},
                            "roles": {"type": "Role[]"}}}
  },
  "relationships": [
    {"from": "POST /api/v1/users", "to": "UserRequest", "type": "REQUEST_BODY", "via": "body", "required": true,
     "description": "POST /api/v1/users accepts UserRequest as its request body"},
    {"from": "User", "to": "Role", "type": "HAS_MANY", "via": "roles",
     "description": "User.roles holds a list of Role"}
  ],
  "workflows": [{"name": "Manage users", "classification": "INFERRED_CRUD", "confidence": "HIGH",
                 "operations": ["POST /api/v1/users", "GET /api/v1/users", "GET /api/v1/users/{id}", "DELETE /api/v1/users/{id}"],
                 "description": "Typical lifecycle of users: create -> list -> get -> delete. ..."}]
}
```
