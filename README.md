# commerce-backend

A RAG-based conversational TV advisor backend. Users describe what they need in natural language and receive product recommendations through a multi-agent AI pipeline backed by hybrid Elasticsearch search. Built as a Spring Boot service intended to run as a B2B SaaS backend or Shopify plugin.

---

## Architecture

```
User query (HTTP POST)
        │
        ▼
  RouterAgent          — classifies intent: new_search | clarify_response |
        │                inspiration_response | decline
        ▼
FilterExtractorAgent   — parses brand, screen size, panel type, price cap,
        │                min rating, sort preference from natural language
        ▼
Embedding (Ollama)     — nomic-embed-text produces a 768-dim vector
        │
        ▼
ElasticsearchSearchService
  ├── kNN (HNSW, cosine)
  ├── BM25 multi-match on name + descriptionForEmbedding
  └── RRF (Reciprocal Rank Fusion) merge
        │
        ▼
  ┌─────────────────── agent loop (max 3 iterations) ──────────────────┐
  │                                                                     │
  │  ResearcherAgent  — analyses candidates, derives requirements       │
  │        │                                                            │
  │  ChallengerAgent  — critiques findings, proposes refinements or     │
  │        │             asks one clarification question                │
  │        │                                                            │
  │  DeciderAgent     — weighs both, picks final products, decides      │
  │                      whether to refine and run another iteration    │
  └─────────────────────────────────────────────────────────────────────┘
        │
        ▼
SSE stream → client   — every pipeline step is emitted as a named event
```

When the query is completely vague ("show me TVs"), the Router triggers an **inspiration flow** that calls `DiversitySearchService`, which queries three price segments (budget ≤ €250, mid ≤ €600, premium >€600) and returns one top-rated product per brand per segment.

Every pipeline run is persisted as a JSON trace file under `data/traces/<queryId>.json`.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Virtual Threads |
| Framework | Spring Boot 3.5 / Spring Web |
| Build | Gradle 8 (Kotlin DSL) |
| Search | Elasticsearch 8.12 — kNN + BM25 + RRF |
| Embeddings | Ollama `nomic-embed-text` (768 dimensions, local) |
| LLM / Agents | LangChain4j 0.36, Google Gemini 2.5 Flash |
| Data mapping | Lombok, Jackson |
| CSV ingestion | OpenCSV |
| Tests | JUnit 5, Testcontainers (Elasticsearch) |

---

## Prerequisites

- **Java 21** (`java -version` must report 21)
- **Gradle 8** (or use the wrapper: `./gradlew`)
- **Docker** with Compose — used to start Elasticsearch
- **Ollama** — runs locally (or on the Windows host if using WSL2)
- **Google Gemini API key** — free tier is sufficient for development

---

## Installation & Setup

### 1. Start Elasticsearch

```bash
docker compose up -d
```

This starts a single-node Elasticsearch 8.12 container on `localhost:9200` with a **trial license** (required for the RRF ranking feature). Data is persisted in a named Docker volume.

To also start Kibana for index inspection:

```bash
docker compose --profile tools up -d
# Kibana available at http://localhost:5601
```

### 2. Start Ollama and pull the embedding model

If Ollama is not already running, start it:

```bash
ollama serve
```

Pull the embedding model (one-time, ~270 MB):

```bash
ollama pull nomic-embed-text
```

> **WSL2 note:** If Ollama runs on the Windows host rather than inside WSL, update `ollama.base-url` in `application.properties` to point to the Windows host gateway IP (e.g., `http://172.26.112.1:11434`). The default value is already set for this case.

### 3. Set the Gemini API key

Export the environment variable before running the application:

```bash
export GEMINI_API_KEY=your_key_here
```

Alternatively, set it directly in `application.properties`:

```properties
gemini.api-key=your_key_here
```

Get a key at [https://aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey).

### 4. Build

```bash
./gradlew build
```

### 5. Run

```bash
./gradlew bootRun
```

The application starts on `http://localhost:8080`.

### 6. Ingest product data

The advisor requires TV product data indexed in Elasticsearch. The bundled ingestion pipeline reads a Kaggle electronics CSV, filters to TV products, converts prices from INR to EUR, generates embeddings via Ollama, and bulk-indexes into the `tv-products` index.

Place your CSV file at `data/electronics_product.csv` (relative to the working directory), then trigger ingestion:

```bash
curl -X POST "http://localhost:8080/api/v1/ingestion/trigger"
```

To use a different file path:

```bash
curl -X POST "http://localhost:8080/api/v1/ingestion/trigger?csvPath=/absolute/path/to/file.csv"
```

Check progress:

```bash
curl "http://localhost:8080/api/v1/ingestion/status"
# {"indexedCount": 1234, "indexName": "tv-products"}
```

Ingestion generates embeddings in batches of 20 and indexes documents in batches of 100. For a large CSV (several thousand products), expect a few minutes depending on Ollama throughput.

### 7. Open the debug UI

A built-in debug interface is served at:

```
http://localhost:8080
```

Use it to send queries and observe the SSE event stream in real time.

---

## Configuration Reference

All settings live in `src/main/resources/application.properties`.

| Key | Default | Description |
|---|---|---|
| `elasticsearch.host` | `localhost` | Elasticsearch hostname |
| `elasticsearch.port` | `9200` | Elasticsearch port |
| `ollama.base-url` | `http://172.26.112.1:11434` | Ollama base URL |
| `ollama.embedding-model` | `nomic-embed-text` | Embedding model name |
| `gemini.api-key` | *(from env `GEMINI_API_KEY`)* | Google Gemini API key |
| `gemini.model` | `gemini-2.5-flash` | Gemini model variant |
| `ingestion.csv.path` | `data/electronics_product.csv` | Default CSV path for ingestion |
| `ingestion.tv.filter` | `true` | Filter CSV to TV products only |
| `ingestion.inr-to-eur-rate` | `0.011` | INR to EUR conversion rate |
| `ingestion.embeddings.enabled` | `true` | Generate embeddings during ingestion |
| `ingestion.embeddings.batch-size` | `20` | Ollama embedding batch size |
| `search.phase0.top-k` | `10` | Number of candidates retrieved per search |
| `search.rrf.rank-constant` | `60` | RRF rank constant |
| `search.rrf.window-size` | `100` | RRF window / kNN numCandidates |
| `search.diversity.segment1-max` | `250.0` | Max price (EUR) for budget segment |
| `search.diversity.segment2-max` | `600.0` | Max price (EUR) for mid segment |
| `search.diversity.products-per-segment` | `3` | Products returned per price segment |
| `rag.max-iterations` | `3` | Maximum agent loop iterations |
| `trace.output-dir` | `data/traces` | Directory for persisted trace JSON files |

---

## API

### POST /api/v1/tv-advisor/query

Starts the RAG pipeline. Returns a `text/event-stream` (SSE) response. The pipeline runs on a virtual thread; the HTTP connection stays open until `stream_complete` is emitted.

**Request body:**

```json
{
  "query": "I need a 55-inch OLED for gaming under €800",
  "queryId": null,
  "parentQueryId": null,
  "history": [],
  "previousProductIds": []
}
```

| Field | Required | Description |
|---|---|---|
| `query` | yes | Natural-language user input |
| `queryId` | no | Client-supplied ID; omit or set `null` for a new conversation |
| `parentQueryId` | no | Reference to the originating query in a multi-turn thread |
| `history` | no | Array of `{role, content}` objects (`"user"` or `"assistant"`) |
| `previousProductIds` | no | Product IDs from the previous response, sent back on a `clarify_response` turn |

**SSE events emitted (in order):**

| Event | Description |
|---|---|
| `query_accepted` | Pipeline started, contains `queryId` |
| `router_decision` | Action chosen by the Router (`new_search`, `clarify_response`, etc.) |
| `phase_results` | Search results for the current iteration; products include `isNew` flag |
| `researcher_findings` | Free-text analysis from the Researcher |
| `challenger_feedback` | Structured critique from the Challenger |
| `decider_verdict` | Final recommendation from the Decider |
| `products_refined` | Shortlist of recommended products after each iteration |
| `pending_clarification` | Challenger needs more info from the user; contains the question |
| `inspire_question` | Query is too vague; offers a diversity showcase |
| `stream_complete` | Pipeline finished |
| `error` | An unhandled exception occurred |

### GET /api/v1/tv-advisor/trace/{queryId}

Returns the full pipeline trace as JSON for the given `queryId`. Reads from `data/traces/<queryId>.json` if the file exists, otherwise returns the in-memory active trace. Returns `404` if neither source has the trace.

### POST /api/v1/ingestion/trigger

Triggers CSV ingestion. Optional `?csvPath=` query parameter overrides the configured default path.

### GET /api/v1/ingestion/status

Returns the current document count in the `tv-products` Elasticsearch index.

---

## License

Apache 2.0
