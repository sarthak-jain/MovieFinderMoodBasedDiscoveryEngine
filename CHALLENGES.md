# MovieFinder Project Challenges

A comprehensive log of every significant challenge encountered during the development and deployment of the MovieFinder application, along with root causes and solutions.

---

## 1. SSE Event Grouping: StepType Overwrites Event Category

**Challenge:**
The workflow panel on the frontend was supposed to group SSE events by request (using `traceId`), displaying each step under its parent request. However, individual workflow steps were never appearing inside their request groups -- they showed up as orphaned entries.

**Root Cause:**
When the frontend received a `workflow-step` SSE event, it parsed the JSON payload and spread it into the event object. The `WorkflowStep` model has a `type` field (e.g., `API_GATEWAY`, `CACHE_CHECK`, `GRAPH_DB_QUERY`). During the JavaScript object spread (`{ ...data, type: 'WORKFLOW_STEP' }`), the intent was to tag the event with a category of `WORKFLOW_STEP` for grouping logic. But because the backend payload also contained a `type` field (the `StepType` enum), the spread order caused the category to be overwritten by the step type, or vice versa. The grouping logic checked `event.type === 'WORKFLOW_STEP'` and never found a match because `type` had been replaced with values like `API_GATEWAY`.

**Solution:**
Renamed the frontend event category field from `type` to `eventType` to avoid collision with the backend's `type` field. The SSE hook now sets `eventType: 'REQUEST_START'` and `eventType: 'WORKFLOW_STEP'` on received events, while `step.type` remains untouched for use in rendering colors and icons. The WorkflowPanel grouping logic was updated to check `eventType` instead of `type`.

**Files changed:**
- `frontend/src/hooks/useWorkflowStream.js` -- uses `eventType` instead of `type` for event category
- `frontend/src/components/WorkflowPanel.jsx` -- groups events by `eventType`

**Commit:** `e766d3f Fix SSE event grouping: prevent StepType from overwriting event category`

---

## 2. Docker Build Failure: `mvnw: not found`

**Challenge:**
The first attempt to build the Docker image for AWS deployment failed immediately at the Maven wrapper step with `/bin/sh: ./mvnw: not found`, even though the `mvnw` file was present and had execute permissions.

**Root Cause:**
The Maven wrapper script (`mvnw`) was committed to Git from a Windows environment with CRLF line endings (`\r\n`). The Docker build used an Alpine Linux base image (`eclipse-temurin:21-jdk-alpine`), which uses `#!/bin/sh` to invoke scripts. The shell interpreter could not parse the script because the carriage return (`\r`) at the end of each line was treated as part of the command, making the script unrecognizable.

**Solution:**
Added `dos2unix` to the Alpine packages installed in the Docker build stage and applied it to the `mvnw` script before execution:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache dos2unix curl
...
RUN dos2unix mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B
```

**File changed:**
- `Dockerfile` -- added `dos2unix` package and conversion step

---

## 3. Docker Build Failure: `curl: not found`

**Challenge:**
After fixing the CRLF issue, the Docker build failed again when the Maven wrapper tried to download the Maven distribution. The error was `curl: not found`.

**Root Cause:**
The Maven wrapper script (`mvnw`) uses `curl` to download the Maven binary when it is not already cached. Alpine Linux is a minimal distribution and does not include `curl` by default. The build stage had no network utilities installed.

**Solution:**
Added `curl` to the same `apk add` command that installs `dos2unix`:

```dockerfile
RUN apk add --no-cache dos2unix curl
```

**File changed:**
- `Dockerfile` -- added `curl` to `apk add` packages

---

## 4. CORS Configuration Blocking CloudFront Frontend

**Challenge:**
After deploying the backend to AWS App Runner and the frontend to S3 + CloudFront, the frontend could not communicate with the backend API. All requests were blocked by CORS policy errors in the browser console.

**Root Cause:**
The `WebConfig.java` CORS configuration had `http://localhost:3000` hardcoded as the only allowed origin. When the frontend was served from the CloudFront distribution domain (e.g., `https://d1234abcdef.cloudfront.net`), the browser's CORS preflight check failed because that origin was not in the allowed list.

**Solution:**
Made the CORS origins configurable via an environment variable (`CORS_ORIGINS`) with a sensible default for local development:

```java
@Value("${CORS_ORIGINS:http://localhost:3000}")
private String corsOrigins;

@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
            .allowedOrigins(corsOrigins.split(","))
            ...
}
```

The App Runner service was then configured with the `CORS_ORIGINS` environment variable set to the CloudFront URL. The comma-separated split allows multiple origins (e.g., both localhost and production).

**File changed:**
- `src/main/java/com/moviefinder/config/WebConfig.java` -- dynamic CORS origins from env var

---

## 5. AWS App Runner Service Not Available

**Challenge:**
When attempting to create the App Runner service via the AWS Console, the App Runner option was not available or the service creation failed with a subscription/activation error.

**Root Cause:**
AWS App Runner requires an initial subscription or service activation in the AWS account before it can be used. Unlike services like EC2 or S3 that are available by default, App Runner needs to be explicitly enabled for the account.

**Solution:**
Navigated to the App Runner service page in the AWS Console and completed the subscription/activation step before creating the service. This was a one-time setup requirement.

---

## 6. Search Does Not Scale: Regex Queries on 10k+ Movies

**Challenge:**
The original search implementation used a Neo4j regex filter (`=~ "(?i).*query.*"`) for title matching. While this worked fine with 30 seed movies, scaling to 10,000+ movies via bulk ingestion would result in a full table scan for every search query, causing unacceptable latency.

**Root Cause:**
Neo4j regex filters with leading wildcards (`.*`) cannot use indexes. Every `MATCH (m:Movie)` node must be loaded and its `title` property evaluated against the regex, resulting in O(n) complexity per search. At 10,000+ movies this would cause multi-second query times.

**Solution:**
Replaced the regex-based search with a Neo4j full-text index. Created the index during ingestion:

```cypher
CREATE FULLTEXT INDEX movie_title_fulltext IF NOT EXISTS
FOR (m:Movie) ON EACH [m.title]
```

Updated the `SearchService.buildCypherQuery()` method to use the full-text index via `db.index.fulltext.queryNodes('movie_title_fulltext', $query)` which returns results with Lucene scoring. The query parameter is passed as `query.trim() + "*"` to enable prefix matching for typeahead functionality.

**Files changed:**
- `src/main/java/com/moviefinder/service/BulkIngestionService.java` -- creates full-text index
- `src/main/java/com/moviefinder/service/SearchService.java` -- uses `db.index.fulltext.queryNodes` instead of regex

**Commit:** `4f06787 Add bulk movie ingestion from TMDb and full-text search`

---

## 7. SIMILAR_TO Cypher Syntax Error: Map Projection on Node Variable

**Challenge:**
The initial Cypher query for computing `SIMILAR_TO` relationships attempted to collect movie pairs into maps and then project fields from them using `pair.m2`. Neo4j rejected this with a syntax error at runtime.

**Root Cause:**
Neo4j's Cypher language does not support map projection (dot-access) on node variables inside collected map literals. The query tried something like:

```cypher
WITH m1, collect({m2: m2, score: toFloat(shared) / 5.0}) AS pairs
UNWIND pairs AS pair
MERGE (m1)-[r:SIMILAR_TO]->(pair.m2)
```

The expression `pair.m2` does not work because Cypher treats `pair` as a map but `m2` is a node reference inside that map, and map property access on node references is not supported in this context.

**Solution:**
Replaced the map-of-pairs approach with parallel `collect()` arrays and index-based access:

```cypher
WITH m1, m2, toFloat(shared) / 5.0 AS score
ORDER BY m1.tmdbId, score DESC
WITH m1, collect(m2)[0..$maxSimilar] AS topMovies, collect(score)[0..$maxSimilar] AS topScores
WITH m1, topMovies, topScores, range(0, size(topMovies)-1) AS idxs
UNWIND idxs AS idx
WITH m1, topMovies[idx] AS m2, topScores[idx] AS score
MERGE (m1)-[r:SIMILAR_TO]->(m2)
SET r.score = score
```

This collects movies and scores into two separate lists, then uses `range()` and `UNWIND` to iterate through both arrays by index position.

**File changed:**
- `src/main/java/com/moviefinder/service/BulkIngestionService.java` -- `computeSimilarTo()` method

---

## 8. SIMILAR_TO Cypher Variable Scope Error: `shared` Out of Scope

**Challenge:**
After fixing the map projection issue, the next iteration of the Cypher query failed because it tried to reference the `shared` variable inside a `collect()` call, but `shared` was no longer in scope at that point in the query pipeline.

**Root Cause:**
In Cypher, a `WITH` clause defines a new scope. Once you aggregate with `collect()`, any non-aggregated variables from the previous scope are lost unless explicitly carried forward. The query had:

```cypher
WITH m1, m2, count(g) AS shared
WHERE shared >= 2
WITH m1, collect(m2)[0..$maxSimilar] AS topMovies, collect(toFloat(shared) / 5.0)[0..$maxSimilar] AS topScores
```

The problem is that `shared` is a per-row value from the previous `WITH`, but `collect()` aggregates rows. Inside the `collect(toFloat(shared) / 5.0)` expression, `shared` cannot be referenced because the aggregation context has changed -- `shared` varies per `m1, m2` pair but the `collect()` is grouping by `m1`.

**Solution:**
Computed the score alias in an intermediate `WITH` clause before the aggregation step, so that the score is already a simple value when `collect()` runs:

```cypher
WITH m1, m2, count(g) AS shared
WHERE shared >= 2
WITH m1, m2, toFloat(shared) / 5.0 AS score
ORDER BY m1.tmdbId, score DESC
WITH m1, collect(m2)[0..$maxSimilar] AS topMovies, collect(score)[0..$maxSimilar] AS topScores
```

By computing `score` from `shared` in its own `WITH` clause, the value is bound to each `(m1, m2)` row. The subsequent `collect(score)` then works correctly because `score` is a simple scalar on each row being collected.

**File changed:**
- `src/main/java/com/moviefinder/service/BulkIngestionService.java` -- `computeSimilarTo()` method

---

## 9. AuraDB Transaction Memory Limit: 278MB Exceeded

**Challenge:**
Running the `SIMILAR_TO` computation as a single query across all 10,000+ movies caused Neo4j AuraDB to reject the transaction with an error indicating the 278MB transaction memory limit was exceeded.

**Root Cause:**
Neo4j AuraDB (the managed cloud database) enforces a per-transaction memory limit of approximately 278MB. The `SIMILAR_TO` query performs a Cartesian-like join across all movies that share genres (`MATCH (m1)-[:HAS_GENRE]->(g)<-[:HAS_GENRE]-(m2)`), producing a very large intermediate result set. With 10,000+ movies and multiple shared genres per pair, the intermediate rows (before aggregation) consumed far more memory than AuraDB allows in a single transaction.

**Solution:**
Batched the `SIMILAR_TO` computation by processing 200 movies at a time instead of all movies in a single query. The approach:

1. First, fetch all movie `tmdbId` values with a lightweight query
2. Split them into batches of 200
3. Run the `SIMILAR_TO` Cypher with `UNWIND $tmdbIds AS id` so each transaction only processes 200 source movies
4. Accumulate edge counts across batches

```java
int similarBatchSize = 200;
for (int i = 0; i < allTmdbIds.size(); i += similarBatchSize) {
    List<Long> batch = allTmdbIds.subList(i, Math.min(i + similarBatchSize, allTmdbIds.size()));
    try (Session session = neo4jDriver.session()) {
        var result = session.run(cypher, Values.value(Map.of("tmdbIds", batch, "maxSimilar", maxSimilar)));
        if (result.hasNext()) {
            totalEdges += result.next().get("edgeCount").asInt();
        }
    }
}
```

This keeps each transaction well within AuraDB's memory limit while still computing the complete similarity graph.

**File changed:**
- `src/main/java/com/moviefinder/service/BulkIngestionService.java` -- `computeSimilarTo()` method, `similarBatchSize = 200`

---

## 10. Typeahead Search and Logo Home Navigation

**Challenge:**
The initial search UI required users to submit a full search query before seeing results. There was no incremental search-as-you-type experience, and no way to reset the application to its initial state by clicking the logo.

**Root Cause:**
The original frontend implementation only triggered a search on form submission (Enter key or button click). The logo in the header was static with no click handler. These were UX gaps rather than bugs, but they became apparent during usability testing.

**Solution:**
Added debounced typeahead search that triggers automatically as the user types (with a short delay to avoid excessive API calls). Also made the logo/brand element clickable to reset the search state and return to the home view.

**Commit:** `4323b8d Add typeahead search and logo home reset`

---

## Summary

| # | Challenge | Category | Impact |
|---|-----------|----------|--------|
| 1 | SSE event grouping broken | Frontend/SSE | Workflow panel unusable |
| 2 | Docker `mvnw: not found` | DevOps/Docker | Build blocked |
| 3 | Docker `curl: not found` | DevOps/Docker | Build blocked |
| 4 | CORS blocking CloudFront | Backend/Config | Frontend cannot reach API |
| 5 | App Runner not available | AWS/Setup | Deployment blocked |
| 6 | Regex search at scale | Backend/Performance | Multi-second queries at 10k movies |
| 7 | Cypher map projection syntax | Backend/Neo4j | SIMILAR_TO computation fails |
| 8 | Cypher variable scope error | Backend/Neo4j | SIMILAR_TO computation fails |
| 9 | AuraDB transaction memory | Backend/Neo4j | SIMILAR_TO computation OOM on cloud |
| 10 | No typeahead or home reset | Frontend/UX | Poor search experience |
