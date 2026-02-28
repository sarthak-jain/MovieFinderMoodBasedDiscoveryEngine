# MovieFinder -- System Design Primer

A comprehensive system design reference for the MovieFinder project, organized for
interview preparation. Each section contains the technical depth you need to discuss
the system confidently, with concrete numbers and trade-off analysis.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Deep Dive](#2-architecture-deep-dive)
3. [Data Model](#3-data-model)
4. [Key Design Decisions & Trade-offs](#4-key-design-decisions--trade-offs)
5. [API Design](#5-api-design)
6. [Resilience Patterns](#6-resilience-patterns)
7. [Data Ingestion Pipeline](#7-data-ingestion-pipeline)
8. [Search & Ranking](#8-search--ranking)
9. [Real-Time Workflow Tracing (SSE)](#9-real-time-workflow-tracing-sse)
10. [Deployment & Infrastructure](#10-deployment--infrastructure)
11. [Scaling Discussion](#11-scaling-discussion)
12. [Numbers to Know](#12-numbers-to-know)

---

## 1. System Overview

### What It Does

MovieFinder is a mood-based movie discovery engine. Users select an emotional mood
(e.g., "cozy," "adrenaline," "mind-bending") and/or type a title query, and the
system returns movies ranked by mood relevance and quality. Every search is
accompanied by a real-time system design workflow panel that visualizes the entire
backend processing pipeline -- cache checks, graph traversals, external API calls,
circuit breaker states, and ranking computations -- as they happen.

### Who It's For

- **End users**: People who want to find a movie based on how they feel, not just
  by genre or title.
- **Interviewers/Engineers**: The split-screen workflow panel is a live demonstration
  of system design concepts -- caching, graph databases, resilience patterns, SSE
  streaming, and API orchestration.

### Key Features

- Mood-based movie discovery across 8 moods with weighted scoring
- Full-text typeahead search with Lucene-backed indexes
- **AI-powered natural language search** via Claude API (e.g., "scary movies for halloween")
- Graph-based "Similar Movies" recommendations via shared-genre traversal
- Real-time workflow visualization via Server-Sent Events (SSE)
- Circuit breaker and rate limiter protecting external API calls (TMDb + Claude)
- Redis caching with tiered TTLs (5 min search, 24h detail, 7d posters, 1h AI parse)
- Bulk ingestion pipeline: TMDb Discover API -> Neo4j with mood scoring
- AWS production deployment: App Runner + AuraDB + Upstash Redis + CloudFront
- Custom domain: findmynextmovie.com (Route via CloudFront with ACM SSL)

### Architecture Diagram

```
+-------------------+         +------------------+         +--------------------+
|                   |  HTTPS  |                  |  SSE    |                    |
|   React SPA       |-------->|  Spring Boot     |~~~~~~~~>|  Workflow Panel    |
|   (S3+CloudFront) |<--------|  (App Runner)    |         |  (same React SPA) |
|                   |  JSON   |                  |         |                    |
+-------------------+         +--------+---------+         +--------------------+
                                       |
                    +------------------+|+------------------+
                    |                   |                   |
              +-----v------+    +------v------+    +-------v--------+
              |            |    |             |    |                |
              |  Neo4j 5   |    |  Redis 7    |    |  TMDb API v3   |
              |  (AuraDB)  |    |  (Upstash)  |    |  (External)    |
              |            |    |             |    |                |
              | - Movies   |    | - Search    |    | - Posters      |
              | - Genres   |    |   cache     |    | - Backdrops    |
              | - Moods    |    | - Poster    |    | - Runtime      |
              | - SIMILAR  |    |   cache     |    | - Credits      |
              |   _TO      |    | - Detail    |    |                |
              |            |    |   cache     |    | Rate: 40/10s   |
              +------------+    | - AI parse  |    +----------------+
                                |   cache     |
                                +-------------+    +----------------+
                                                   |  Claude API    |
                                                   |  (Anthropic)   |
                                                   |                |
                                                   | - NL query     |
                                                   |   parsing      |
                                                   | - Haiku model  |
                                                   | - Structured   |
                                                   |   extraction   |
                                                   +----------------+

Data Flow (Search Request):

  User        React         Spring Boot       Redis         Neo4j        TMDb
   |            |               |               |             |            |
   |--search--->|               |               |             |            |
   |            |--GET /search->|               |             |            |
   |            |               |--cache GET--->|             |            |
   |            |               |<--MISS--------|             |            |
   |            |               |--Cypher-------|------------>|            |
   |            |               |<--movies------|-------------|            |
   |            |               |--enrich(posters)------------|----------->|
   |            |               |<--poster URLs---------------|------------|
   |            |               |--rank movies--|             |            |
   |            |               |--cache SET--->|             |            |
   |            |<--JSON--------|               |             |            |
   |<--render---|               |               |             |            |
   |            |               |               |             |            |
   |  (SSE events streamed to workflow panel throughout)      |            |
```

---

## 2. Architecture Deep Dive

### Component Breakdown

| Component | Technology | Role |
|-----------|-----------|------|
| Frontend | React 18 SPA | Movie search UI + workflow visualization panel |
| Backend | Spring Boot 3.2.3 (Java 21) | REST API, SSE streaming, orchestration |
| Graph DB | Neo4j 5 (AuraDB in prod) | Movie/genre/mood storage, graph traversals, full-text search |
| Cache | Redis 7 (Upstash in prod) | Search results, movie details, poster URLs, AI parse cache |
| External API | TMDb API v3 | Poster images, backdrops, runtime, credits |
| AI/LLM | Claude Haiku (Anthropic API) | Natural language query parsing into structured search parameters |
| CDN | S3 + CloudFront | Static frontend hosting (findmynextmovie.com) |
| Container Runtime | AWS App Runner | Auto-scaling backend containers |

### Why Each Technology Was Chosen

**Neo4j (Graph DB)** -- Movies have inherently relational data: genres, moods,
similarity. The core queries are traversals: "find all movies connected to mood X
with score > 0.7" and "find movies sharing 2+ genres." Graph databases handle these
multi-hop relationship queries in O(k) where k is the number of edges traversed,
versus O(n) JOIN scans in relational databases. Neo4j also provides built-in
Lucene full-text indexes, eliminating the need for a separate search service.

**Redis (Cache Layer)** -- Sub-millisecond reads for repeated queries. The tiered
TTL strategy (5 min for volatile search results, 7 days for stable poster URLs)
balances freshness against cache hit rate. Redis also serves as the poster URL
cache, reducing TMDb API calls by ~80% after warm-up.

**SSE (Server-Sent Events)** -- The workflow panel needs a unidirectional real-time
stream from server to client. SSE is simpler than WebSockets (no bidirectional
handshake, no frame parsing, works over standard HTTP/1.1), auto-reconnects
natively in the browser via EventSource API, and is sufficient since the client
never sends data back on this channel.

**Spring Boot** -- Mature ecosystem for Neo4j (spring-data-neo4j), Redis
(spring-data-redis), and SSE (SseEmitter). Constructor injection, @Async for
bulk ingestion, and @EventListener for startup seeding.

**TMDb API** -- Industry-standard movie metadata API with 600k+ movies, high-quality
poster images, and a generous free tier (40 requests/10 seconds).

### Request Flow (Standard Search)

```
1. [API Gateway]     Route GET /api/search -> SearchService.search()
2. [Cache Check]     Redis GET "search:cozy::0" -> HIT or MISS
3. [Graph Query]     Cypher: MATCH (m)-[r:MATCHES_MOOD]->(mood) ... ORDER BY r.score
4. [External API]    TMDb batch enrichment -- poster/backdrop for each movie
5. [Circuit Breaker] Check state: CLOSED/OPEN/HALF_OPEN
6. [Rate Limiter]    Check quota: remaining/40 in 10s window
7. [Ranking]         score = moodScore * 0.6 + normalizedRating * 0.4
8. [Cache Write]     Redis SET "search:cozy::0" TTL 5min
9. [Response]        200 OK -- 20 results (total: 45ms)
```

### Request Flow (AI-Powered Search)

```
1. [API Gateway]      Route POST /api/search/ai -> AiSearchService + SearchService
2. [AI Query Parser]  Claude Haiku parses "scary movies for halloween"
                      -> { mood: "dark", genres: ["Horror"], searchTerms: [] }
3. [Cache Check]      Redis GET "ai-result:dark::horror:0" -> HIT or MISS
4. [Graph Query]      Genre + mood filtered Cypher (8 query variants)
5. [External API]     TMDb batch enrichment
6. [Circuit Breaker]  Check state for both TMDb and Claude APIs
7. [Ranking]          Weighted scoring on AI-filtered results
8. [Cache Write]      Redis SET with 5min TTL
9. [Response]         200 OK -- results + AI interpretation for transparency
```

---

## 3. Data Model

### Neo4j Graph Schema

```
Node Types:
  (:Movie)  -- tmdbId, title, year, overview, avgRating, posterPath, popularity
  (:Genre)  -- name
  (:Mood)   -- name, description, emoji
  (:Person) -- name (optional, populated for detail views)

Relationships:
  (Movie)-[:HAS_GENRE]->(Genre)
  (Movie)-[r:MATCHES_MOOD {score: 0.0..1.0}]->(Mood)
  (Movie)-[r:SIMILAR_TO {score: 0.0..1.0}]->(Movie)
  (Person)-[r:ACTED_IN {role: "..."}]->(Movie)   [detail queries only]
  (Person)-[:DIRECTED]->(Movie)                   [detail queries only]

Example Graph (subset):

  (Inception)--[:HAS_GENRE]-->(Sci-Fi)<--[:HAS_GENRE]--(The Matrix)
       |                                                    |
       +--[:MATCHES_MOOD {0.95}]-->(mind-bending)<--[:MATCHES_MOOD {0.95}]--+
       |                                                    |
       +--[:SIMILAR_TO {0.6}]------------------------------+
```

### Indexes and Constraints

```cypher
-- Uniqueness constraints (also create implicit indexes)
CREATE CONSTRAINT movie_tmdbId_unique FOR (m:Movie) REQUIRE m.tmdbId IS UNIQUE
CREATE CONSTRAINT genre_name_unique   FOR (g:Genre) REQUIRE g.name IS UNIQUE
CREATE CONSTRAINT mood_name_unique    FOR (m:Mood)  REQUIRE m.name IS UNIQUE

-- Performance indexes
CREATE FULLTEXT INDEX movie_title_fulltext FOR (n:Movie) ON EACH [n.title]
CREATE INDEX movie_rating_idx FOR (m:Movie) ON (m.avgRating)
```

### Why a Graph DB Over Relational

| Aspect | Graph (Neo4j) | Relational (PostgreSQL) |
|--------|--------------|------------------------|
| "Movies matching mood X" | Single traversal: O(k) | JOIN movie_moods ON ... WHERE: O(n) |
| "Similar movies to Y" | 1-hop SIMILAR_TO edge | Self-JOIN via junction table: O(n^2) worst case |
| "Movies sharing 2+ genres" | Pattern match with count | Multi-table JOIN + GROUP BY + HAVING |
| Schema flexibility | Add new relationships without migration | ALTER TABLE + new junction tables |
| Full-text search | Built-in Lucene index | Requires pg_trgm or external Elasticsearch |

The relationship-first data model means the most expensive operations (mood matching,
similarity computation, multi-hop recommendations) are native graph traversals rather
than complex SQL JOINs. At 10k movies with ~29k mood edges and thousands of SIMILAR_TO
edges, the difference is significant.

---

## 4. Key Design Decisions & Trade-offs

### Graph DB vs Relational vs Document Store

**Decision**: Neo4j graph database.

- **vs PostgreSQL**: Relational would require junction tables for Movie-Genre,
  Movie-Mood, and Movie-Movie (SIMILAR_TO). The core query "find movies matching mood
  X, sorted by mood score, with genre info" would be a 3-table JOIN. In Neo4j, it is
  a single pattern match. The SIMILAR_TO fallback (shared-genre traversal:
  Movie->Genre<-Movie) would be a self-JOIN in SQL but is a natural 2-hop traversal
  in a graph.

- **vs MongoDB**: Document store would embed genres/moods inside movie documents,
  but "find all movies for mood X" becomes a collection scan. Cross-document
  relationships (SIMILAR_TO) require application-level joins or denormalization.

- **Trade-off**: Neo4j has a smaller operational ecosystem than PostgreSQL. AuraDB
  has strict memory limits (278MB per transaction) that required batching the
  SIMILAR_TO computation.

### SSE vs WebSockets vs Polling

**Decision**: Server-Sent Events (SSE).

- **vs WebSockets**: The workflow panel is strictly server-to-client (unidirectional).
  WebSockets would add unnecessary complexity: full-duplex handshake, frame parsing,
  ping/pong keep-alives, and no automatic reconnection. SSE reconnects automatically
  via the browser's EventSource API with a 3-second backoff.

- **vs Polling**: Polling would require the client to repeatedly request workflow
  state, adding latency (interval gap) and wasted requests (when no workflow is
  active). SSE pushes events the instant they occur, providing true real-time
  visualization.

- **Trade-off**: SSE is limited to ~6 concurrent connections per browser (HTTP/1.1).
  This is fine for a single-user demo but would need WebSockets or HTTP/2 for
  multi-tab production use.

### Full-Text Index vs Regex for Search

**Decision**: Neo4j full-text index (Lucene-backed) with prefix matching.

- **Original approach**: `WHERE m.title =~ "(?i).*query.*"` -- O(n) full scan of
  every Movie node. Works at 30 movies, unusable at 10,000+.

- **Current approach**: `CALL db.index.fulltext.queryNodes('movie_title_fulltext', $query)`
  -- Lucene inverted index with O(log n) lookups. Query parameter appended with `*`
  enables prefix matching for typeahead.

- **Trade-off**: Full-text index requires index creation at ingestion time and
  consumes additional storage. Lucene scoring is less controllable than custom regex
  logic, but the performance gain (milliseconds vs seconds) is decisive.

### Async Bulk Ingestion vs Startup Loading

**Decision**: Both. Small seed data loads synchronously on startup; bulk ingestion
runs asynchronously via admin API.

- **Startup seeding**: 30 hardcoded movies with manually curated mood scores. Uses
  `@EventListener(ApplicationReadyEvent.class)` with MERGE for idempotency. Ensures
  the app is usable immediately after deployment.

- **Bulk ingestion**: Admin endpoint `POST /api/admin/ingest?pages=500` triggers
  `@Async` processing. Fetches from TMDb Discover API, writes movies in batches of
  500, computes mood scores algorithmically, and builds SIMILAR_TO edges in batches
  of 200.

- **Trade-off**: Dual-path ingestion adds code complexity but provides the best
  developer experience (instant data on startup) and scalability (10k+ movies via
  bulk ingest).

### Circuit Breaker + Rate Limiter for TMDb API

**Decision**: Custom implementations of both patterns protecting all TMDb calls.

- **Circuit Breaker**: 5 consecutive failures -> OPEN state -> 30s timeout ->
  HALF_OPEN -> 1 success -> CLOSED. Returns empty map as fallback (graceful
  degradation -- movies display without posters).

- **Rate Limiter**: Fixed-window algorithm, 40 requests per 10 seconds (matching
  TMDb's documented limit). Excess requests are skipped, not queued.

- **Trade-off**: Custom implementations over libraries (Resilience4j) keep
  dependencies minimal and the code easy to explain in interviews. The rate limiter
  uses a fixed window rather than sliding window, which can allow brief bursts at
  window boundaries.

### Redis Caching Strategy

**Decision**: Tiered TTLs with key-prefix namespacing.

| Cache Tier | Key Pattern | TTL | Rationale |
|-----------|-------------|-----|-----------|
| Search results | `search:{mood}:{query}:{page}` | 5 min | Volatile -- new movies may be ingested |
| Movie details | `movie:detail:{id}` | 24 hours | Stable data, enriched with TMDb |
| TMDb detail | `tmdb:detail:{tmdbId}` | 24 hours | Upstream data changes rarely |
| Poster URLs | `tmdb:poster:{tmdbId}` | 7 days | Image URLs are essentially permanent |
| Similar movies | `similar:{movieId}` | 1 hour | Computed from stable graph edges |

- **Serialization**: Jackson JSON with type information (`DefaultTyping.NON_FINAL`)
  so deserialization preserves concrete types.
- **Invalidation**: Cache flush via `DELETE /api/admin/cache` clears all `search:*`
  keys. Poster and detail caches are left intact (TTL-based expiry).
- **Failure handling**: All cache operations are wrapped in try/catch. Cache failures
  are non-fatal -- the system falls through to the graph DB.

### Mood Scoring Algorithm

**Decision**: Genre-weighted vector summation with popularity bonus.

```
For each genre of a movie:
  Look up genre -> mood weight vector (e.g., "Action" -> {adrenaline: 0.9, dark: 0.2})
  Sum weights across all genres

Normalize by genre count:
  moodScore = sum(weights) / genreCount

Apply popularity bonus:
  moodScore += min(popularity / 1000, 1.0) * 0.05   // max 5% bonus

Clamp to [0, 1] and filter by minimum threshold (0.25)
```

This produces 2-4 mood edges per movie. For example, a Sci-Fi Thriller gets high
scores for "mind-bending" (0.8 + 0.4 = 1.2 / 2 = 0.6) and "adrenaline"
(0.4 + 0.7 = 1.1 / 2 = 0.55).

---

## 5. API Design

### Endpoints

All endpoints are prefixed with `/api` and follow RESTful conventions.

#### Search

```
GET /api/search?mood={mood}&query={text}&page={n}

Parameters:
  mood  (optional) -- one of: cozy, dark, mind-bending, feel-good, adrenaline,
                       romantic, nostalgic, thought-provoking
  query (optional) -- title search text (prefix-matched via full-text index)
  page  (optional) -- 0-indexed pagination, default 0

Response 200:
{
  "movies": [
    {
      "id": 42,
      "tmdbId": 27205,
      "title": "Inception",
      "year": 2010,
      "overview": "A thief who steals...",
      "avgRating": 8.4,
      "posterPath": "https://image.tmdb.org/t/p/w500/...",
      "moodScore": 0.95,
      "relevanceScore": 0.91
    }
    // ... up to 20 results
  ],
  "totalResults": 147,
  "page": 0,
  "totalPages": 8,
  "mood": "mind-bending",
  "query": null,
  "searchTimeMs": 42
}
```

#### AI-Powered Search

```
POST /api/search/ai
Content-Type: application/json

Body:
{
  "query": "scary movies for halloween",
  "page": "0"
}

Response 200:
{
  "interpretation": {
    "mood": "dark",
    "searchTerms": [],
    "genres": ["Horror"],
    "explanation": "Looking for scary/horror movies with a dark halloween feel"
  },
  "results": {
    "movies": [ ... ],    // same format as standard search
    "totalResults": 42,
    "page": 0,
    "searchTimeMs": 850
  }
}

Response 503: AI service unavailable (circuit breaker open or API key missing)
```

The interpretation object is returned for transparency -- the frontend displays what
the AI understood, so users can refine their query if the parsing was off.

#### Typeahead Suggestions

```
GET /api/suggest?q={partial_title}

Response 200:
[
  { "id": 42, "title": "Inception", "year": 2010 },
  { "id": 91, "title": "Interstellar", "year": 2014 }
]
// Max 8 results, requires q.length >= 2
```

#### Movie Detail

```
GET /api/movie/{id}

Response 200:
{
  "id": 42,
  "tmdbId": 27205,
  "title": "Inception",
  "year": 2010,
  "overview": "...",
  "avgRating": 8.4,
  "posterPath": "https://image.tmdb.org/t/p/w500/...",
  "backdropPath": "https://image.tmdb.org/t/p/original/...",
  "runtime": 148,
  "tagline": "Your mind is the scene of the crime.",
  "genres": ["Action", "Sci-Fi", "Thriller"],
  "moods": [
    { "mood": "mind-bending", "score": 0.95 },
    { "mood": "adrenaline", "score": 0.8 }
  ],
  "cast": [
    { "name": "Leonardo DiCaprio", "role": "Cobb" }
  ],
  "directors": ["Christopher Nolan"]
}

Response 404: (empty body)
```

#### Similar Movies

```
GET /api/movie/{id}/similar

Response 200:
[
  {
    "id": 91,
    "title": "The Matrix",
    "year": 1999,
    "avgRating": 8.7,
    "posterPath": "..."
  }
  // ... up to 10 results
]
```

#### Moods

```
GET /api/moods

Response 200:
[
  {
    "name": "cozy",
    "description": "Warm, comforting films perfect for a rainy day",
    "emoji": "blanket"
  }
  // ... 8 moods total
]
```

#### Workflow Stream (SSE)

```
GET /api/workflow/stream
Content-Type: text/event-stream

event: request-start
data: {"type":"REQUEST_START","traceId":"a1b2c3d4","method":"GET","path":"/api/search?mood=cozy"}

event: workflow-step
data: {"traceId":"a1b2c3d4","stepNumber":1,"type":"API_GATEWAY","name":"API Gateway",
       "detail":"Routing GET /api/search -> SearchService.search()","durationMs":1}

event: workflow-step
data: {"traceId":"a1b2c3d4","stepNumber":2,"type":"CACHE_CHECK","name":"Cache MISS",
       "detail":"Redis LOOKUP search:cozy::0 -> MISS","cacheStatus":"MISS","durationMs":2}

// ... additional steps for graph query, external API, ranking, cache write, response
```

#### Health Check

```
GET /api/health

Response 200:
{
  "status": "UP",
  "neo4j": { "status": "UP", "movieCount": 10247 },
  "redis": { "status": "UP" },
  "tmdb": { "status": "UP", "circuitBreaker": "CLOSED", "rateLimitRemaining": 37 }
}
```

#### Admin Endpoints

```
POST /api/admin/ingest?pages=500     -- Start bulk ingestion (async)
GET  /api/admin/ingest/status        -- Check ingestion progress
POST /api/admin/ingest/similar       -- Recompute SIMILAR_TO edges only
POST /api/admin/indexes              -- Create/verify indexes
DELETE /api/admin/cache              -- Flush search cache
```

---

## 6. Resilience Patterns

### Circuit Breaker

Protects all TMDb API calls. Implemented as a generic `CircuitBreaker<T>` with
atomic state management for thread safety.

```
State Machine:

  +--------+    5 failures     +------+    30s timeout    +-----------+
  | CLOSED |------------------>| OPEN |------------------>| HALF_OPEN |
  +--------+                   +------+                   +-----------+
       ^                          |                            |
       |                          | (within 30s)               |
       |                          +--- returns fallback -------+
       |                                                       |
       +---------- 1 success (resets failure count) -----------+
       |                                                       |
       +---------- 1 failure (back to OPEN) -------------------+

Configuration:
  - Failure threshold: 5 consecutive failures
  - Reset timeout: 30 seconds
  - Fallback: Collections.emptyMap() (movie displays without poster/metadata)

Thread Safety:
  - State: AtomicReference<State>
  - Failure count: AtomicInteger
  - Last failure time: AtomicLong
```

### Rate Limiter

Fixed-window rate limiter protecting TMDb API from exceeding its quota.

```
Algorithm: Fixed Window Counter

  Window: 10 seconds
  Max requests: 40 (matches TMDb's documented limit)

  tryAcquire():
    if (now - windowStart > 10s):
      reset window, reset counter
    if (counter < 40):
      counter++, return true
    else:
      log warning, return false

  When rate limit is hit:
    - Movie enrichment is skipped for remaining movies in batch
    - Movie displays with graph DB data only (no poster)
    - Workflow panel shows "Rate Limiter" step with remaining quota
```

### Graceful Degradation

The system degrades gracefully at multiple levels:

```
Level 1: Redis down
  -> Cache operations fail silently (try/catch)
  -> Every request hits Neo4j directly
  -> System is slower but fully functional

Level 2: TMDb API down
  -> Circuit breaker opens after 5 failures
  -> Movies display without posters/backdrops
  -> Graph DB data (title, year, rating, genres, moods) is complete
  -> Circuit breaker auto-recovers after 30s

Level 3: TMDb rate limited
  -> Rate limiter prevents excess calls
  -> Remaining movies in batch display without posters
  -> Poster cache (7d TTL) serves most repeated requests

Level 4: Neo4j read failure
  -> Health endpoint reports "DEGRADED"
  -> Search returns error (no fallback for primary data store)
```

### Cache-First Strategy

Every read path follows the same pattern:

```
1. Build cache key from request parameters
2. Try Redis GET
   - Success + data present -> return cached data (CACHE HIT)
   - Failure or null -> proceed to primary data source (CACHE MISS)
3. Query Neo4j / call TMDb API
4. Try Redis SET with appropriate TTL
   - Failure -> log warning, continue (non-fatal)
5. Return fresh data
```

---

## 7. Data Ingestion Pipeline

### Overview

Two ingestion paths:

1. **Startup seed**: 30 hardcoded movies with curated mood scores (idempotent,
   runs on `ApplicationReadyEvent`).
2. **Bulk ingestion**: Admin-triggered async pipeline fetching up to 10,000 movies
   from TMDb Discover API.

### Bulk Ingestion Pipeline

```
Phase 1: FETCHING           Phase 2: WRITING_MOVIES
+-------------------+      +------------------------+
| TMDb Discover API |      | Neo4j MERGE (batches   |
| /discover/movie   |----->| of 500)                |
| pages 1..500      |      | UNWIND $movies AS m    |
| 250ms delay/page  |      | MERGE (movie:Movie     |
| dedup by tmdbId   |      |   {tmdbId: m.tmdbId})  |
+-------------------+      | + MERGE HAS_GENRE      |
                            +------------------------+
                                       |
Phase 3: WRITING_MOODS      Phase 4: COMPUTING_SIMILAR
+------------------------+  +-----------------------------+
| For each movie:        |  | Batches of 200 movies:      |
|   MoodScoringEngine    |  | MATCH (m1)-[:HAS_GENRE]->   |
|   .computeMoodScores() |  |   (g)<-[:HAS_GENRE]-(m2)   |
|   Filter >= 0.25       |  | WHERE shared >= 2           |
| MERGE MATCHES_MOOD     |  | Top 20 per movie            |
| edges in batches of 500|  | MERGE SIMILAR_TO edges      |
+------------------------+  +-----------------------------+
```

### TMDb Discover API Pagination

```
URL: /discover/movie?sort_by=popularity.desc&page={1..500}&vote_count.gte=50

- Each page returns 20 movies (max 500 pages = 10,000 movies)
- 250ms delay between pages to stay within rate limits
- Deduplication via HashSet<Long> of seen tmdbIds
- Filters: minimum 50 votes (quality threshold)
- Extracts: id, title, release_date, overview, vote_average, popularity,
  poster_path, genre_ids
```

### Mood Scoring Engine

The `MoodScoringEngine` maps TMDb genre IDs to genre names and computes mood
scores using a predefined weight matrix:

```
Genre -> Mood Weight Matrix (subset):

              adrenaline  dark  mind-bending  feel-good  cozy  romantic  nostalgic  thought-provoking
Action        0.9         0.2   --            0.2        --    --        --         --
Sci-Fi        0.4         --    0.8           --         --    --        --         0.6
Thriller      0.7         0.6   0.4           --         --    --        --         --
Comedy        --          --    --            0.8        0.5   --        0.2        --
Romance       --          --    --            0.6        0.5   0.9       --         --
Horror        0.5         0.9   0.3           --         --    --        --         --
Animation     --          --    --            0.7        0.7   --        0.5        --

Algorithm:
  1. For each genre of the movie, look up its mood weight vector
  2. Sum all weights per mood across all genres
  3. Normalize by genre count: score = sum / genreCount
  4. Add popularity bonus: score += min(popularity/1000, 1.0) * 0.05
  5. Clamp to [0, 1]
  6. Filter: only keep scores >= 0.25 (minMoodScore)
```

### SIMILAR_TO Computation

```
Strategy: Shared genre count, batched for memory constraints

Cypher:
  MATCH (m1)-[:HAS_GENRE]->(g)<-[:HAS_GENRE]-(m2)
  WHERE m1.tmdbId < m2.tmdbId      -- avoid duplicate pairs
  WITH m1, m2, count(g) AS shared
  WHERE shared >= 2                 -- minimum 2 shared genres
  WITH m1, m2, toFloat(shared)/5.0 AS score
  ORDER BY score DESC
  WITH m1, collect(m2)[0..20] AS topMovies    -- max 20 similar per movie
  MERGE (m1)-[r:SIMILAR_TO]->(m2)

Batching:
  - Process 200 movies per transaction (AuraDB 278MB tx limit)
  - Total: ~50 batches for 10k movies
  - Progress logged every 10 batches
```

### Idempotent Operations

All write operations use Cypher `MERGE` instead of `CREATE`:

```cypher
MERGE (movie:Movie {tmdbId: $tmdbId})    -- creates or matches
SET movie.title = $title, ...             -- always updates properties
```

This makes the entire pipeline re-runnable without duplicating data.

---

## 8. Search & Ranking

### Neo4j Full-Text Index

```
Index: movie_title_fulltext (Lucene-backed)
Fields: Movie.title
Query: db.index.fulltext.queryNodes('movie_title_fulltext', $query)

Features:
  - Prefix matching: query + "*" enables typeahead (e.g., "incep*")
  - Lucene scoring: relevance-ranked results out of the box
  - Case-insensitive by default
  - Tokenized: matches individual words in multi-word titles
```

### Search Query Construction

The search service dynamically builds Cypher based on which parameters are provided:

```
4 query variants:

1. Mood + Query:  Full-text search filtered by mood relationship
   CALL db.index.fulltext.queryNodes(...) YIELD node, score
   MATCH (node)-[r:MATCHES_MOOD]->(mood {name: $mood})
   ORDER BY score DESC, r.score DESC

2. Query only:    Full-text search, no mood filter
   CALL db.index.fulltext.queryNodes(...) YIELD node, score
   ORDER BY score DESC, avgRating DESC

3. Mood only:     Traverse mood relationships
   MATCH (m)-[r:MATCHES_MOOD]->(mood {name: $mood})
   ORDER BY r.score DESC, avgRating DESC

4. Browse all:    No filters
   MATCH (m:Movie)
   ORDER BY avgRating DESC

All variants: SKIP $skip LIMIT $limit (page size = 20)
```

### AI-Powered Natural Language Search

Users can toggle AI mode and type natural language queries like "movie with wizards"
or "romantic comedies from the 90s." The system uses Claude Haiku to parse these into
structured search parameters.

```
Architecture:

  User Input: "scary movies for halloween"
       |
       v
  AiSearchService.parseQuery()
       |
       +-- Redis cache check: "ai-search:{SHA256(input)}" (1h TTL)
       |   HIT -> return cached parse
       |   MISS -> call Claude API
       |
       v
  Claude Haiku API (structured extraction)
       |
       +-- System prompt provides:
       |     - 8 available moods with descriptions
       |     - 19 available genres
       |     - Instructions to return JSON only
       |
       +-- Response: { mood: "dark", genres: ["Horror"], searchTerms: [], explanation: "..." }
       |
       +-- Cache result in Redis (1h TTL)
       |
       v
  SearchService.aiSearch(mood, searchTerms, genres, page)
       |
       +-- Builds genre-filtered Cypher (8 query variants)
       |   based on which parameters are present:
       |
       |   mood + query + genres:
       |     CALL db.index.fulltext.queryNodes(...) YIELD node, score
       |     MATCH (node)-[r:MATCHES_MOOD]->(mood {name: $mood})
       |     MATCH (node)-[:HAS_GENRE]->(g:Genre) WHERE g.name IN $genres
       |
       |   genres only:
       |     MATCH (m:Movie)-[:HAS_GENRE]->(g:Genre) WHERE g.name IN $genres
       |     ORDER BY m.avgRating DESC
       |
       |   ... (6 more combinations)
       |
       v
  Results + AI interpretation returned to frontend
```

**Why Claude Haiku?**
- Fast (~500-800ms) and cheap (~$0.001 per query) for structured extraction
- Understands nuance: "wizards" -> Fantasy genre, "feel-good rainy day" -> cozy mood
- Returns structured JSON matching the app's existing search parameters

**Resilience:**
- Circuit breaker wraps Claude API calls (same pattern as TMDb)
- If Claude is down, frontend falls back to regular title search
- Redis caches AI parse results for 1 hour (same query = instant on repeat)

**Cost model:**
- Claude Haiku: ~$0.25/1M input tokens, ~$1.25/1M output tokens
- Average query: ~500 input tokens, ~100 output tokens = ~$0.0003 per query
- $5 credit supports ~15,000 AI searches

### Ranking Formula

```
relevanceScore = moodScore * 0.6 + normalizedRating * 0.4

Where:
  moodScore        = MATCHES_MOOD edge score (0.0 - 1.0), default 0.5
  normalizedRating = avgRating / 10.0 (scales 0-10 rating to 0-1)

Example:
  Inception: mood "mind-bending" score 0.95, rating 8.4
  relevance = 0.95 * 0.6 + 0.84 * 0.4 = 0.57 + 0.336 = 0.906

  Forrest Gump: mood "mind-bending" score 0 (no edge), rating 8.5
  relevance = 0.5 * 0.6 + 0.85 * 0.4 = 0.30 + 0.34 = 0.64

Weighting rationale:
  - 60% mood: user intent is the primary signal
  - 40% rating: quality is a secondary signal to avoid showing poor-rated
    movies that happen to match the mood
```

### Pagination

```
Page size: 20 movies
Total pages: ceil(totalResults / 20)

Count query runs separately (same WHERE clause, RETURN count(m) AS count)
Client passes page=0, page=1, etc.
Server applies SKIP (page * 20) LIMIT 20
```

---

## 9. Real-Time Workflow Tracing (SSE)

### How the Split-Screen UI Works

```
+------------------------------------------+---------------------------+
|                                          |                           |
|  Main Panel (left ~65%)                  |  Workflow Panel (right)   |
|                                          |                           |
|  +------------------------------------+ |  System Design Panel      |
|  | How are you feeling?               | |  Live workflow viz        |
|  |                                    | |                           |
|  | [cozy] [dark] [mind-bending] ...   | |  Legend:                  |
|  |                                    | |  * Gateway  * Cache       |
|  | [Search by title...    ] [Search]  | |  * Graph DB * External    |
|  +------------------------------------+ |  * CB  * Rate  * Ranking  |
|                                          |                           |
|  +------------------------------------+ |  GET /api/search?mood=... |
|  |  [Poster] [Poster] [Poster]        | |  #a1b2c3d4                |
|  |  Inception Matrix   Interstellar   | |  +-----------------------+|
|  |  2010      1999     2014           | |  |[1] API Gateway   1ms  ||
|  |                                    | |  |[2] Cache MISS    2ms  ||
|  |  [Poster] [Poster] [Poster]        | |  |[3] Graph Query  18ms  ||
|  |  Fight     Parasite Dune           | |  |    Cypher: MATCH ...   ||
|  |  Club      2019     2021           | |  |[4] TMDb API     120ms ||
|  +------------------------------------+ |  |[5] Circuit Breaker     ||
|                                          |  |    State: CLOSED       ||
|                                          |  |[6] Rate Limiter        ||
|                                          |  |    Quota: 37/40        ||
|                                          |  |[7] Ranking      1ms   ||
|                                          |  |[8] Cache Write   3ms  ||
|                                          |  |[9] Response     45ms  ||
|                                          |  |    200 OK - 20 results||
|                                          |  +-----------------------+|
+------------------------------------------+---------------------------+
```

### Data Flow: WorkflowTracer -> WorkflowEmitter -> SSE Broadcast

```
                  SearchService                   WorkflowTracer
                       |                               |
  1. search() called   |                               |
  2. ------------------>  startTrace("GET", "/api/...") |
                        |  returns Trace object         |
                        |                               |
  3. cache check        |  trace.emitCacheCheck(...)    |
                        |         |                     |
                        |         v                     |
                        |  WorkflowStep.cacheCheck()    |
                        |         |                     |
                        |         v                     |
                        |  emitter.broadcast(step)      |
                        |         |                     |
                        |         v                     |
                        |  for each SseEmitter:         |
                        |    emitter.send(event(        |
                        |      name: "workflow-step",   |
                        |      data: JSON               |
                        |    ))                         |
                        |         |                     |
                        |         v                     |
                        |  Browser EventSource          |
                        |    receives event             |
                        |         |                     |
                        |         v                     |
                        |  useWorkflowStream hook       |
                        |    adds to events array       |
                        |         |                     |
                        |         v                     |
                        |  WorkflowPanel re-renders     |
                        |    groups by traceId          |
```

### Step Types

| StepType | Color | Icon | When Emitted |
|----------|-------|------|-------------|
| API_GATEWAY | Blue | Door | Request routing decision |
| AI_QUERY_PARSER | Magenta | Brain | Claude API parses natural language query |
| CACHE_CHECK | Amber | Disk | Redis GET for cached result |
| CACHE_WRITE | Amber | Disk | Redis SET after computing result |
| GRAPH_DB_QUERY | Purple | Database | Neo4j Cypher execution |
| EXTERNAL_API | Light Blue | Globe | TMDb API call |
| CIRCUIT_BREAKER | Red | Lightning | After each TMDb/Claude interaction |
| RATE_LIMIT | Orange | Timer | After each TMDb interaction |
| RANKING | Green | Chart | After scoring algorithm runs |
| RESPONSE | Green | Check | Final response sent |
| ERROR | Red | X | Any error during processing |

### SSE Implementation Details

```java
// WorkflowEmitter.java
SseEmitter emitter = new SseEmitter(0L);  // no timeout (infinite)
emitters = new CopyOnWriteArrayList<>();  // thread-safe for concurrent broadcasts

// Dead emitter cleanup: on each broadcast, catch IOException -> mark for removal
// Client lifecycle: onCompletion, onTimeout, onError -> remove from list
// Auto-reconnect: browser EventSource reconnects after 3s on error
```

### Frontend Event Grouping

Events are grouped by `traceId` into request groups. The `eventType` field
(set by the frontend hook, NOT the backend `type` field) distinguishes between
`REQUEST_START` and `WORKFLOW_STEP` events. This avoids a naming collision where
the backend `type` field (e.g., `API_GATEWAY`) would overwrite the event
category during JavaScript object spread.

---

## 10. Deployment & Infrastructure

### Docker Multi-Stage Build

```dockerfile
# Stage 1: Build (JDK Alpine)
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache dos2unix curl    # Fix CRLF + Maven download
COPY .mvn pom.xml mvnw ./
RUN dos2unix mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Runtime (JRE Alpine -- smaller image)
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Key decisions:
- **Multi-stage**: Build stage includes full JDK + Maven; runtime stage only JRE.
  Final image is ~200MB vs ~500MB single-stage.
- **dos2unix**: Required because `mvnw` was committed with Windows CRLF line
  endings; Alpine's `/bin/sh` cannot parse `\r`.
- **dependency:go-offline**: Caches Maven dependencies in a separate layer,
  so code changes do not re-download all dependencies.

### AWS Architecture

```
                                    AWS Cloud (us-west-2, Oregon)
+------------------------------------------------------------------+
|  All backend services co-located in us-west-2 for low latency    |
|                                                                  |
|  +------------------+         +---------------------------+      |
|  |  S3 Bucket       |         |  App Runner               |      |
|  |  (React build)   |         |  (Docker container)       |      |
|  +--------+---------+         |                           |      |
|           |                   |  Spring Boot 3.2.3        |      |
|  +--------v---------+         |  Java 21                  |      |
|  |  CloudFront CDN  |         |  Port 8080                |      |
|  |  (Global edge)   |         |  Auto-scaling: 1-25       |      |
|  |  HTTPS + caching |         |  vCPU: 1, RAM: 2GB        |      |
|  +------------------+         +------+------+------+------+      |
|                                      |      |      |             |
|                               +------v-+  +-v------v------+     |
|                               |Upstash |  | Neo4j AuraDB  |     |
|                               |Redis   |  | (managed)     |     |
|                               |Oregon  |  |               |     |
|                               |Server- |  | 2GB RAM       |     |
|                               |less    |  | 4GB storage   |     |
|                               |TLS     |  | Neo4j 5       |     |
|                               +--------+  +---------------+     |
|                                                                  |
+------------------------------------------------------------------+
Uptime: UptimeRobot pings /api/health every 5 min (keeps container warm)

Environment Variables (App Runner):
  NEO4J_URI         = neo4j+s://xxxxx.databases.neo4j.io
  NEO4J_USERNAME    = neo4j
  NEO4J_PASSWORD    = (secret)
  REDIS_URL         = rediss://default:xxxxx@xxxxx.upstash.io:6379
  TMDB_API_KEY      = (secret)
  ANTHROPIC_API_KEY = (secret)
  CORS_ORIGINS      = https://findmynextmovie.com,https://www.findmynextmovie.com,https://d1234abcdef.cloudfront.net
  SPRING_PROFILES_ACTIVE = prod

Custom Domain:
  findmynextmovie.com -> CloudFront (ACM SSL certificate, ALIAS record via Porkbun)
```

### Component Choices

| Component | Service | Why |
|-----------|---------|-----|
| Backend hosting | AWS App Runner (us-west-2) | Managed container hosting with auto-scaling. Co-located in Oregon with Neo4j and Redis for <5ms inter-service latency. UptimeRobot keeps container warm. |
| Graph database | Neo4j AuraDB (Free, us-west-2) | Managed Neo4j 5 with 2GB RAM, 4GB storage. Handles 10k movies with room for growth. Automatic backups. |
| Cache | Upstash Redis (us-west-2) | Serverless Redis with TLS in Oregon. Co-located with App Runner and Neo4j. Pay-per-request pricing for low-traffic demo. |
| AI/LLM | Anthropic Claude Haiku | Fast, cheap structured extraction. Parses natural language queries into mood/genre/title parameters. ~$0.0003 per query. |
| Frontend CDN | S3 + CloudFront | Static hosting with custom domain (findmynextmovie.com). CloudFront provides HTTPS, edge caching, and global distribution. |

### Production Profile

```yaml
# application-prod.yml
spring:
  neo4j:
    uri: ${NEO4J_URI}                  # neo4j+s:// (TLS)
    authentication:
      username: ${NEO4J_USERNAME}
      password: ${NEO4J_PASSWORD}
    pool:
      max-connection-lifetime: 30m       # recycle before proxies kill idle connections
      idle-time-before-connection-test: 1m # validate stale connections before reuse
      connection-acquisition-timeout: 30s
      max-connection-pool-size: 50
      log-leaked-sessions: true
  data:
    redis:
      url: ${REDIS_URL}               # rediss:// (TLS)
      ssl:
        enabled: true
```

**Connection pool tuning** prevents `SessionExpiredException` errors caused by
stale TCP connections. When App Runner scales down and back up, or during idle
periods, intermediate load balancers may silently drop connections.
`idle-time-before-connection-test` ensures dead connections are detected and
replaced before use.

---

## 11. Scaling Discussion

### Current Bottlenecks

| Bottleneck | Current State | Mitigation |
|-----------|--------------|------------|
| Neo4j single instance | All reads/writes to one AuraDB instance | Read replicas (AuraDB Professional) |
| TMDb rate limit | 40 req/10s shared across all users | Poster cache (7d TTL) reduces API calls ~80% |
| Single App Runner instance | 1 vCPU, 2GB RAM at minimum scale | Auto-scales to 25 instances |
| Full-text search | Lucene index on single Neo4j instance | Elasticsearch for dedicated search |
| SIMILAR_TO computation | O(n^2) for all pairs, batched at 200 | Pre-compute offline, incremental updates |
| SSE connections | CopyOnWriteArrayList, all in-memory | Redis Pub/Sub for multi-instance broadcast |

### Horizontal Scaling Strategy

```
Current (Demo Scale):
  1 App Runner instance -> 1 AuraDB -> 1 Upstash Redis

Scaled (Production):
  N App Runner instances (auto-scaled by request count)
  |
  +-> Neo4j AuraDB Professional (read replicas)
  |     Primary: writes (ingestion, SIMILAR_TO)
  |     Replicas: reads (search, detail, similar)
  |
  +-> Redis Cluster (Upstash Pro or ElastiCache)
  |     Sharded by key prefix
  |     search:* -> shard 0
  |     tmdb:*   -> shard 1
  |     similar:* -> shard 2
  |
  +-> Redis Pub/Sub (for SSE fan-out)
        When App Runner scales to N instances, SSE events
        must be broadcast to all instances. Publish workflow
        events to a Redis channel; each instance subscribes
        and forwards to its local SSE clients.
```

### Potential Improvements

**Search**: Replace Neo4j full-text index with Elasticsearch for:
- Fuzzy matching ("Inseption" -> "Inception")
- Boosted fields (title^3, overview^1)
- Faceted search by genre, year range, rating range
- Better relevance scoring (BM25 + custom signals)

**Personalization**: Add user accounts and track viewing/search history:
- Collaborative filtering: "users who liked X also liked Y"
- User-specific mood profiles: adjust weights based on past preferences
- Neo4j is ideal for this -- add User nodes with WATCHED/LIKED edges

**Recommendation engine**: Upgrade from shared-genre similarity:
- Content-based: TF-IDF on overviews, cosine similarity on genre vectors
- Graph-based: Node2Vec embeddings on the movie graph, k-NN for similar
- Hybrid: weighted combination of content + collaborative signals

**Real-time ingestion**: Replace batch ingestion with streaming:
- TMDb webhook or polling for new releases
- Kafka topic for incoming movies
- Stream processor computes mood scores and writes to Neo4j
- Cache invalidation via Redis pub/sub

---

## 12. Numbers to Know

### Data Scale

| Metric | Value |
|--------|-------|
| Total movies (after bulk ingest) | ~10,000 |
| Genres | 19 |
| Moods | 8 |
| MATCHES_MOOD edges | ~29,000 (avg 2.9 per movie) |
| SIMILAR_TO edges | ~15,000-20,000 (max 20 per movie, min 2 shared genres) |
| HAS_GENRE edges | ~25,000 (avg 2.5 genres per movie) |
| Seed movies (startup) | 30 (hardcoded with curated scores) |

### Latency Estimates

All services co-located in us-west-2 (Oregon). Inter-service latency <5ms.
Before region co-location, cross-country Neo4j queries added ~250-300ms overhead.

| Operation | Cold (cache miss) | Warm (cache hit) |
|-----------|--------------------|-------------------|
| Mood search (20 results) | 30-80ms | 2-5ms |
| Title search (full-text) | 15-40ms | 2-5ms |
| AI search (NL query) | 800-1500ms (Claude + graph) | 30-80ms (parse cached) |
| Movie detail | 50-200ms (TMDb enrichment) | 2-5ms |
| Similar movies | 20-60ms | 2-5ms |
| Typeahead suggestion | 5-15ms | N/A (no cache) |
| SSE event delivery | <5ms per step | N/A |

### Cache Strategy

| Cache Tier | TTL | Expected Hit Rate |
|-----------|-----|-------------------|
| Search results | 5 min | 40-60% (popular moods) |
| AI query parse | 1 hour | 60-80% (repeated NL queries) |
| AI search results | 5 min | 40-60% (same as standard) |
| Movie details | 24 hours | 70-85% (frequently viewed) |
| Poster URLs | 7 days | 85-95% (stable URLs) |
| Similar movies | 1 hour | 50-70% |

### TMDb API Budget

```
Rate limit: 40 requests / 10 seconds (per API key)
Bulk ingestion: 500 pages * 1 req/page + 250ms delay = ~125 seconds
Search enrichment: 20 movies * 1 req/movie = 20 requests (within one window)
Cache reduces ongoing API calls by ~80% after warm-up
```

### Infrastructure Specs (AWS)

| Resource | Specification | Cost Tier |
|----------|--------------|-----------|
| App Runner | 1 vCPU, 2GB RAM, auto-scale 1-25 | Pay per use |
| AuraDB Free | 2GB RAM, 4GB storage, 1 instance | Free |
| Upstash Redis | 256MB, 10k commands/day (free) | Free |
| S3 | Static hosting, ~5MB build | Pennies/month |
| CloudFront | Edge caching, HTTPS | Free tier eligible |

### Key Thresholds to Discuss

- **AuraDB transaction memory limit**: 278MB -- this is why SIMILAR_TO computation
  must be batched at 200 movies per transaction.
- **Circuit breaker threshold**: 5 failures -> OPEN -> 30s recovery. Tuned to
  tolerate brief TMDb outages without permanently opening.
- **Minimum mood score**: 0.25 -- below this, the mood edge is not created.
  Prevents weak, noisy connections in the graph.
- **Page size**: 20 movies -- balances payload size with TMDb enrichment cost
  (20 API calls per page in worst case).
- **SSE reconnect**: 3 seconds -- fast enough for good UX, slow enough to avoid
  hammering a down server.
- **Search debounce**: 200ms -- balances responsiveness with API call reduction
  for typeahead.

---

## Interview Talking Points Cheat Sheet

**"Walk me through what happens when a user searches."**
> Request hits the Spring Boot controller, which builds a cache key from mood +
> query + page. Redis cache check -- on miss, we build a Cypher query dynamically
> (one of 4 variants depending on parameters), execute it against Neo4j's full-text
> index and mood edges, then batch-enrich results with TMDb posters through a circuit
> breaker and rate limiter. Results are ranked (60% mood, 40% rating), cached for
> 5 minutes, and returned. Throughout, each step emits an SSE event to the workflow
> panel.

**"How does the AI search work?"**
> When the user toggles AI mode and types a natural language query like "scary movies
> for halloween," the request goes to a separate POST endpoint. First, we check Redis
> for a cached parse of that exact query (SHA-256 hashed key, 1h TTL). On miss, we
> call Claude Haiku with a structured prompt that lists all available moods and genres,
> asking it to return JSON with mood, genres, searchTerms, and an explanation. The
> parsed result is cached, then fed into a genre-filtered Cypher query (8 variants
> depending on which parameters Claude extracted). The response includes both the
> search results and the AI's interpretation, so users can see what the AI understood
> and refine their query. The whole pipeline is wrapped in a circuit breaker -- if
> Claude is down, the frontend falls back to regular title search transparently.

**"Why Neo4j over PostgreSQL?"**
> The core data model is relationships: movies connect to genres, moods, and other
> movies. The key queries are graph traversals -- "find movies matching mood X" is a
> single-hop traversal, and "find similar movies" is a 2-hop traversal through
> shared genres. In PostgreSQL, these become multi-table JOINs with junction tables.
> Neo4j also provides a built-in Lucene full-text index, so we get search without
> adding Elasticsearch.

**"How do you handle the TMDb API going down?"**
> Three layers of protection. First, a rate limiter prevents exceeding the 40 req/10s
> quota. Second, a circuit breaker opens after 5 consecutive failures and stops
> making calls for 30 seconds. Third, poster URLs are cached in Redis for 7 days, so
> most requests never hit TMDb at all. When TMDb is truly down, movies display with
> all graph data intact -- just no poster images.

**"How would you scale this to 1M movies?"**
> Three changes. Replace Neo4j full-text search with Elasticsearch for better
> relevance scoring and fuzzy matching. Add Neo4j read replicas to distribute query
> load. Use Redis Pub/Sub for SSE fan-out across multiple App Runner instances. The
> SIMILAR_TO computation would move to an offline batch job using Neo4j's Graph Data
> Science library for more sophisticated similarity algorithms.

**"Why SSE instead of WebSockets?"**
> The workflow panel is strictly server-to-client -- the user never sends data back
> on this channel. SSE is simpler (standard HTTP, no upgrade handshake), reconnects
> automatically via the browser's EventSource API, and works through HTTP proxies
> without special configuration. WebSockets would be overkill for a unidirectional
> stream.
