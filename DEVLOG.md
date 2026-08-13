# 🛠️ Engineering DevLog — Cost-Efficient RAG System

**Project:** Cost-Efficient Retrieval-Augmented Generation (RAG) Platform  
**Author:** Keshav Bansal ([@keshavbansal02](https://github.com/keshavbansal02))  
**Stack:** Java 17 | Spring Boot 3.3.5 | Spring AI | Neon PostgreSQL (PgVector HNSW) | Google Gemini / OpenAI  
**Target:** Applied AI / ML Engineering Take-Home Benchmark  

---

## 📑 Table of Contents
1. [Project Overview & System Architecture](#1-project-overview--system-architecture)
2. [Milestone 1: Architectural Design & Store Selection](#milestone-1-architectural-design--store-selection)
3. [Milestone 2: Idempotent Ingestion & Hash Deduplication](#milestone-2-idempotent-ingestion--hash-deduplication)
4. [Milestone 3: Grounded Retrieval & Zero-Hallucination Fallback](#milestone-3-grounded-retrieval--zero-hallucination-fallback)
5. [Milestone 4: AOP Performance & Token Monitoring](#milestone-4-aop-performance--token-monitoring)
6. [Milestone 5: Automated Evaluation Suite (IR Metrics + LLM Judge)](#milestone-5-automated-evaluation-suite-ir-metrics--llm-judge)
7. [Milestone 6: Financial Cost Modeling & Scale Analysis](#milestone-6-financial-cost-modeling--scale-analysis)
8. [Milestone 7: Containerization, CI/CD & Deployment](#milestone-7-containerization-cicd--deployment)
9. [Milestone 8: Interactive Web Dashboard](#milestone-8-interactive-web-dashboard)
10. [Milestone Progress Summary](#milestone-progress-summary)

---

## 1. Project Overview & System Architecture

### Architectural Pipeline
```
[Client / UI / REST]
       │
       ├── Ingestion Stream
       │     ├── Multi-format Reader (PDF, HTML, MD, TXT)
       │     ├── Sliding Window Chunker (512 size / 64 overlap)
       │     ├── SHA-256 Hash Generation -> Deterministic UUID
       │     ├── DB Lookup: `SELECT id FROM vector_store WHERE id = ?`
       │     └── IF NEW -> Embed & Insert into Neon PgVector (HNSW Index)
       │         IF DUP -> Skip Vector Embedding ($0 API Cost)
       │
       ├── Query & Retrieval Stream
       │     ├── Vector Similarity Search (Top-K, Cosine Distance)
       │     ├── Context Filtering & Threshold Check
       │     ├── IF Context Empty -> Return Fallback ($0 LLM Cost)
       │     └── IF Context Found -> Strict Grounded Prompt -> LLM -> Answer + [Doc N, filename] Citations
       │
       └── Monitoring & Quality Harness
             ├── Spring AOP Interceptor -> Latency (ms), Tokens, Chunk Count
             ├── Evaluation Engine -> Recall@K, MRR, nDCG@K, Faithfulness, Relevance
             └── Cost Analysis Engine -> 100K / 1M / 10M TCO Projections
```

---

## Milestone 1: Architectural Design & Store Selection

### Objective
Evaluate and select an optimal low-cost vector storage backend to replace expensive always-on managed vector database pods (e.g., Pinecone Standard Pods at ~$70–$140+/mo baseline).

### Trade-off Analysis & Store Selection Matrix

| Vector Store | Type | Idle Cost | Hybrid Data (SQL + Vector) | Java / Spring AI Support | Production Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Neon (PgVector)** | **Serverless Cloud Postgres** | **$0.00 (Auto-suspend)** | **✅ Native (ACID Relational + HNSW Vector in 1 DB)** | **⭐⭐⭐⭐⭐ Production Ready** | **Selected (Optimal for Java / Enterprise Stack)** |
| **Qdrant** | Dedicated Vector DB (Rust) | ~$25–$45/mo (Cloud cluster) | ❌ Vectors only; requires separate SQL DB | ⭐⭐⭐ gRPC / REST | High performance at 10M+ scale, but continuous cloud pod cost |
| **LanceDB** | Embedded / S3 Columnar | $0.00 (Storage only) | 🟡 Columnar only; no relational joins | ⭐⭐ Experimental bindings in Java | Ideal for Python disk/S3 workflows |
| **ChromaDB / FAISS** | In-Memory / File Store | $0.00 | ❌ No ACID transactions or multi-user locking | ⭐⭐ Client only | Limited concurrent production scaling |
| **Pinecone** | Managed Cloud Pods | ~$70–$280+/mo (Always-on) | ❌ Vectors only; high cost for idle/light query indexes | ⭐⭐⭐⭐ Fully managed | Costly baseline for light query workloads |

### Decision Record
* **Selected Backend:** **Neon Serverless PostgreSQL with `pgvector` extension**
* **Rationale:** Serverless compute scaling drops idle costs to zero while preserving full ACID transaction semantics and sub-millisecond hash lookups in the same database table.

---

## Milestone 2: Idempotent Ingestion & Hash Deduplication

### Objective
Prevent redundant vector embedding generation costs when re-ingesting documents or updating corpora with overlapping content.

### Implementation Details
* **Document Parsers:**
  - `PDF`: Apache PDFBox / Spring AI `PagePdfDocumentReader`
  - `HTML`: Jsoup HTML sanitizer and text extractor
  - `Markdown & Text`: Clean UTF-8 stream tokenizer
* **Chunking Configuration:**
  - Default Chunk Size: `512 tokens / characters`
  - Default Overlap: `64 tokens / characters`
* **Deduplication Algorithm:**
  1. For each chunk $C_i$, compute cryptographic hash: $H(C_i) = \text{SHA-256}(C_i.\text{content})$.
  2. Generate deterministic UUID: $\text{UUID}_i = \text{UUID.nameUUIDFromBytes}(H(C_i))$.
  3. Query Postgres `vector_store` table by `id`.
  4. If UUID exists, increment `duplicateChunksSkipped` and **bypass embedding API call**.
  5. If new, invoke OpenAI / Gemini embedding model and persist vector + metadata.

---

## Milestone 3: Grounded Retrieval & Zero-Hallucination Fallback

### Objective
Ensure answers are strictly grounded in retrieved evidence, cite source documents, and prevent LLM hallucinations on out-of-domain queries.

### Grounding & Fallback Flow
```
User Query
    │
    ▼
Top-K Similarity Search (Cosine Distance in PgVector)
    │
    ├── Score < SimilarityThreshold OR Chunks == 0?
    │       ├── YES ──► "No relevant context found in stored documents." ($0 LLM Token Cost)
    │       └── NO  ──► Construct Grounded Prompt:
    │                      - System: "Answer ONLY using provided context. Cite [Doc N, filename]."
    │                      - Context: Chunks with metadata
    │                      - User Query
    │                      ▼
    │                   LLM Generation
    │                      ▼
    │                   Grounded Response + Exact Citations
```

---

## Milestone 4: AOP Performance & Token Monitoring

### Objective
Zero-overhead operational observability logging per-query latency, chunk count, and token usage without polluting service business logic.

### Implementation: `QueryLoggingAspect.java`
* Leverages Spring Aspect-Oriented Programming (`@Around` advice on `RagService.query`).
* Measures:
  - Total Query Execution Time ($ms$)
  - Retrieved Document Chunk Count
  - Prompt Tokens, Completion Tokens, Total Tokens
  - Grounded Status Flag

---

## Milestone 5: Automated Evaluation Suite (IR Metrics + LLM Judge)

### Objective
Deliver an automated evaluation harness with mathematical Information Retrieval (IR) metrics and LLM-as-a-Judge answer quality grading on a 15-question benchmark dataset (`eval_dataset.json`).

### Evaluation Metrics Matrix

| Metric Category | Metric | Mathematical Formula / Method | Target Score |
| :--- | :--- | :--- | :---: |
| **Retrieval IR** | **Recall@K (Hit Rate)** | $\text{Recall}@K = \frac{|\text{Retrieved Chunks} \cap \text{Gold Chunks}|}{|\text{Gold Chunks}|}$ | **> 0.90** |
| **Retrieval IR** | **Mean Reciprocal Rank (MRR)** | $\text{MRR} = \frac{1}{|Q|} \sum_{i=1}^{|Q|} \frac{1}{\text{rank}_i}$ | **> 0.85** |
| **Retrieval IR** | **nDCG@K** | $\text{nDCG}@K = \frac{\text{DCG}@K}{\text{IDCG}@K}, \quad \text{DCG}@K = \sum_{i=1}^K \frac{2^{\text{rel}_i}-1}{\log_2(i+1)}$ | **> 0.88** |
| **Retrieval IR** | **Context Precision** | $\text{Precision}@K = \frac{|\text{Relevant Chunks in Top } K|}{K}$ | **> 0.85** |
| **Answer Quality** | **Faithfulness / Groundedness** | LLM-as-a-Judge: Measures if answer claims are directly supported by context (0.0 to 1.0) | **> 0.92** |
| **Answer Quality** | **Answer Relevance** | LLM-as-a-Judge: Measures if answer directly addresses the prompt without fluff (0.0 to 1.0) | **> 0.90** |
| **Latency** | **P50 / P95 Percentile** | Percentile distribution over query execution times | **P50 < 200ms, P95 < 400ms** |

---

## Milestone 6: Financial Cost Modeling & Scale Analysis

### Objective
Prove with numbers that PgVector (Neon PostgreSQL) is an economically credible alternative to dedicated managed vector databases.

### Cost Comparison Projections (1536-dim vectors, 50K queries/mo)

| Scale Tier | Vector Count | Index Storage (GB) | Neon PgVector ($/mo) | Managed DB / Pinecone ($/mo) | Monthly Savings ($) | Net Savings (%) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **100K** | 100,000 | 0.8 GB | **$10.00** | $70.00 | **$60.00** | **85.7%** |
| **1M** | 1,000,000 | 8.0 GB | **$35.00** | $280.00 | **$245.00** | **87.5%** |
| **10M** | 10,000,000 | 80.0 GB | **$150.00** | $1,600.00 | **$1,450.00** | **90.6%** |

---

## Milestone 7: Containerization, CI/CD & Deployment

### Implementation Summary
* **Multi-Stage `Dockerfile`:**
  - Build Stage: `eclipse-temurin:17-jdk-alpine` (layer cached Maven build)
  - Runtime Stage: `eclipse-temurin:17-jre-alpine` (lightweight, unprivileged `spring` user)
* **CI/CD Pipeline (`.github/workflows/ci.yml`):**
  - Automated JDK 17 setup with Maven cache
  - Clean compile & test verification on every pull request / push
  - Docker container build validation
* **1-Click Cloud Deployment (`render.yaml`):**
  - Ready for Render.com or Railway.app automated container deployment

---

## Milestone 8: Interactive Web Dashboard

### Frontend Features (`src/main/resources/static/index.html`)
* **Live Ingestion Hub:** Drag-and-drop file upload with chunk size & overlap sliders.
* **Grounded RAG QA Console:** Interactive query tester displaying answer, confidence score, source citations, latency badge, and token breakdown.
* **Evaluation Center:** Real-time trigger for the 15-question evaluation suite with visual IR metric progress bars.
* **Cost Savings Simulator:** Interactive vector volume slider calculating instant annual financial savings.

---

## Milestone 9: Multimodal Ingestion & Vision OCR

### Objectives & Architecture
* **Scanned Document Detection:** Seamlessly identifies scanned image-based PDFs when native digital text extraction yields $<50$ characters.
* **Multimodal Extraction:** Integrates `GeminiVisionOcrService` rendering PDF pages at 150 DPI and dispatching them to **Gemini 1.5 Flash** for complete verbatim transcription.
* **Structured Tables & Charts:** Transcribes tabular layouts into structured Markdown tables and generates comprehensive chart trend descriptions and label extractions.
* **Direct Image Formats:** Native support for `PNG`, `JPEG`, `JPG`, and `WEBP` image files in the ingestion pipeline.

---

## Milestone 10: Smart Chunking Engine

### Objectives & Architecture
* **Section-Aware Header Splitting:** Splits documents logically based on Markdown header tiers (`#`, `##`, `###`), preventing paragraphs from merging across boundaries.
* **Table-Aware Extraction:** Extracts and isolates Markdown tables intact, saving them as undivided chunks to preserve row alignment.
* **Semantic Sentence Chunking:** Performs vector similarity clustering of sentences using `EmbeddingModel`. Consecutive sentences are grouped until cosine similarity falls below $0.82$, ensuring semantic boundaries.

---

## Milestone 11: Hybrid Search & RRF Reranking

### Objectives & Architecture
* **Dense Vector Search:** Uses PgVector cosine similarity Top-$2K$ retrieval to capture contextual meaning.
* **Sparse Lexical Search:** Integrates PostgreSQL native **Full-Text Search (FTS)** matching exact terms and acronyms, falling back to clean `ILIKE` keyword matching.
* **Reciprocal Rank Fusion (RRF):** Implements in-database RRF rerank scorer fusing dense and sparse search rankings using standard constant $k=60$. Bubbles up high-recall exact matches and semantic fits.

---

## Milestone 12: Confidence Scoring & Hallucination Guard

### Objectives & Architecture
* **Confidence Rating System:** Fuses 30% vector semantic cosine similarity overlap against source chunks and 70% LLM-as-a-Judge groundedness verification rating (faithfulness / hallucination risk) to compute a score from 0.0% to 100.0%.
* **Correct Fallback Accuracy:** Set to 100.0% confidence automatically when zero-context fallback triggers (successfully preventing hallucination).
* **UI Confidence Gauge:** Renders color-coded confidence score indicators dynamically next to the Grounded/Fallback badges in the chat assistant panel.

---

## Milestone Progress Summary

| Milestone | Area | Status | Deliverable |
| :---: | :--- | :---: | :--- |
| **M1** | Store Selection & DB Connection | ✅ Completed | Neon PgVector HNSW table & schema initialized |
| **M2** | Ingestion & SHA-256 Deduplication | ✅ Completed | Multi-format parser + idempotent hash check |
| **M3** | RAG Query & Fallback Engine | ✅ Completed | Strict grounding & zero-context fallback |
| **M4** | AOP Observability Interceptor | ✅ Completed | Query latency, chunk & token usage logging |
| **M5** | Evaluation Harness (IR + LLM) | ✅ Completed | Recall@K, MRR, nDCG@K, Faithfulness, Relevance |
| **M6** | Financial Cost Analysis Engine | ✅ Completed | 100K/1M/10M cost projection models & API |
| **M7** | Dockerfile & CI/CD Workflow | ✅ Completed | Multi-stage Docker + GitHub Actions pipeline |
| **M8** | Embedded Web Dashboard UI | ✅ Completed | Modern glassmorphic responsive interface |
| **M9** | Multimodal Vision OCR & Tables | ✅ Completed | Gemini Flash OCR, table markdown & image parsing |
| **M10** | Smart Chunking Engine | ✅ Completed | Section-aware, table-aware, and semantic sentence boundary splitting |
| **M11** | Hybrid Search & RRF Reranking | ✅ Completed | Dense PgVector search combined with Postgres Full-Text Search via RRF |
| **M12** | Confidence Scoring & Hallucination Guard | ✅ Completed | Cosine similarity + LLM alignment judge scoring & UI confidence meter |




