# DevStation — Phase 5 Implementation Plan
## AI Provider System + Model Management

**Status**: Planning complete → implementation
**Phase boundary**: Infrastructure + chat test surface ONLY. No agents, no tools, no file/terminal/browser access for AI.

---

## 1. Existing Architecture Review (Phases 1–4)

Inspected before planning:

- **Phase 1**: Room `DevStationDatabase` v2 (projects, project_files via filesystem, conversations, messages, app_settings, recent_files, editor_settings), `KeystoreCredentialStore` (EncryptedSharedPreferences + AES256-GCM MasterKey), `AppContainer` manual DI, `DispatcherProvider` abstraction, `ConversationRepository`/`MessageDao` (`MessageRole.USER/ASSISTANT/SYSTEM`).
- **Phase 2**: `TerminalEngine`/`TerminalManager` (`future/terminal`), pipe-bridge processes, ANSI parser.
- **Phase 3**: `future/runtime` Alpine PRoot runtime, `LinuxRuntimeManager`, apk wrapper.
- **Phase 4**: `feature/editor` atomic-save editor; `LanguageService` interface showing the codebase's "decoupled interface" convention.
- **Network deps today**: none. Coroutines 1.8.1, Room 2.6.1, Compose BOM 2024.06.00, Kotlin 1.9.24, AGP 8.2.2, KSP.

Key conventions to respect:
- Interfaces + manual DI in `AppContainer`, ViewModels built with explicit factories, `Result`-returning repository methods, `StateFlow` UI state, dispatchers injected via `DispatcherProvider`.

---

## 2. Goals (from Phase 5 spec)

Secure, extensible, provider-agnostic AI layer:

1. Common `AIProvider` interface + `AIProviderManager` registry (UI/agent never sees provider JSON).
2. Capability-driven adapters: OpenAI-compatible (chat completions), Google Gemini, Anthropic (Messages API).
3. Normalized request/message/response-event/error models; SSE parsing internal to adapters.
4. Streaming via Flow events; non-streaming fallback (Started → TextDelta(full) → Completed) without fake tokenization.
5. Cancellation (structured concurrency), timeouts, bounded retry w/ exponential backoff on transient errors only.
6. API keys ONLY in Android Keystore-backed `SecureCredentialStore`; Room stores credential *references*.
7. Room v3 migration: `ai_provider_configs`, `ai_model_cache`, `ai_usage_records`, `ai_settings`.
8. Conversation integration: reuse existing conversations/messages tables; per-conversation provider+model columns.
9. UI: AI Providers screen, provider detail/setup, AI settings, upgraded chat screen with streaming/cancel/retry.
10. HTTPS enforcement, redacted logging, offline handling, no provider access to files/terminal/Linux.

---

## 3. Architecture

```
UI (Compose, Material 3)
  ConversationsScreen / ConversationDetailScreen (chat)   AiProvidersScreen / ProviderDetailScreen / AiSettingsScreen
        │ StateFlow                                                        │
        ▼                                                                  ▼
ChatOrchestrator (chat use-cases: send / retry / cancel / persistence)   AiSettingsRepository
        │
        ▼
AIProviderManager  ── registry, default provider/model, enabled checks, health status
        │
        ▼
AIProvider (interface: getModels / testConnection / generate Flow)
        │
        ├── OpenAICompatibleProvider   (POST {base}/chat/completions, GET {base}/models, Bearer)
        ├── GeminiProvider             (v1beta generateContent / streamGenerateContent?alt=sse / models, x-goog-api-key)
        └── AnthropicProvider          (POST /v1/messages stream:true, x-api-key + anthropic-version)
        │
        ▼
AIHttpClient (OkHttp singleton; connect/read/write/call timeouts; SSE line reader; redacted logging)
        │
        ▼
HttpsUrlValidator (HTTPS-only, blocks loopback/link-local except explicit allowInsecureLocalHost=false)

Persistence (Room v3)                        Secrets (existing SecureCredentialStore)
  ai_provider_configs (credentialId ref)  ──►  alias "ai_credential_<uuid>" → API key
  ai_model_cache, ai_usage_records, ai_settings
```

### Interfaces (new `core/ai/` package — mirrors `future/` contract style)

```kotlin
interface AIProvider {
    val providerId: String
    val displayName: String
    val capabilities: AIProviderCapabilities
    suspend fun getModels(): Result<List<AIModel>>
    suspend fun testConnection(): Result<ProviderHealth>
    fun generate(request: AIRequest): Flow<AIResponseEvent>
}

interface AIProviderManager {
    val providers: List<AIProvider>
    fun getProvider(id: String): AIProvider?
    fun registerProvider(provider: AIProvider)
    suspend fun resolveModel(providerId: String, modelId: String?): Result<AIModel>
}
```

- `AIProviderManager` gets `AIHttpClient` + `SecureCredentialStore` + DAOs only. It never sees
  `TerminalManager`, `LinuxRuntimeManager`, `EditorFileManager`, or filesystem services
  (spec §43/§44 enforced by construction).

### Capabilities
`AIProviderCapabilities(chat, streaming, vision, toolCalling=false, embeddings, modelListing, usageReporting, systemPrompt, maxContext, reasoning, fileInput)` —
Phase 5 keeps `toolCalling=false` everywhere (spec §45).

---

## 4. Models (provider-independent)

- `AIMessage(role, content, timestamp, id, metadata: Map<String,String>)` — roles SYSTEM/USER/ASSISTANT; future multimodal via `metadata` (no vision now).
- `AIRequest(conversationId, modelId, messages, systemInstruction?, temperature?, maxOutputTokens?, stream, metadata)` — no attachments in Phase 5 (empty list reserved).
- `AIModel(providerId, modelId, displayName, contextWindow?, capabilities, inputPricing?, outputPricing?, enabled, lastUpdated)` — pricing null unless verified (never invented).
- `AIResponseEvent`: `Started(providerId, modelId)` / `TextDelta(text)` / `ThinkingDelta(text)` / `Completed(messageId, stopReason, finishMessage?)` / `Usage(usage: AIUsage)` / `Error(error: AIError)` / `Cancelled`.
- `AIUsage(inputTokens?, outputTokens?, totalTokens?, cachedTokens?, reasoningTokens?)` — null when provider doesn't report; cost shown only when usage AND verified pricing both exist, else "Cost unavailable".
- `AIError` sealed class: Network, Authentication, Authorization, RateLimit(retryAfterSeconds), InvalidRequest, ModelNotFound, ServerError, Timeout, Cancelled, ProviderUnavailable, UnsupportedFeature, Unknown — each with sanitized human message.

## 5. Network Layer

- **OkHttp 4.12.0** (Apache-2.0, mature, Android-compatible, no transitive Kotlin stdlib conflict) + built-in `okhttp` logging only ever at `BASIC` with a **redacting interceptor** (`Authorization`, `x-api-key`, `x-goog-api-key`, `api-key`, `Cookie` → `[REDACTED]`). No request bodies logged by default.
- `AIHttpClient`: single OkHttpClient, per-call `connectTimeout/readTimeout/writeTimeout/callTimeout` from `AiNetworkSettings` (defaults 15s/120s/30s/180s — no infinite requests). Returns `AIHttpResult`.
- `SseLineParser`: pure, testable parser (`feed(line): SseEvent?`) — handles `data:`/`event:` fields, `[DONE]` sentinel, multi-line data, CRLF. Unit-tested.
- `RetryPolicy(maxRetries=2, baseDelay=500ms, maxDelay=4s)`: retries ONLY connection-reset / IOException / HTTP 429 / 5xx; never 401/403/400/404; honors `Retry-After`; exponential backoff; coroutine-cancellation aware.

## 6. Provider Adapters (verified formats)

| Provider | Endpoint (verified from official docs) | Auth | Streaming |
|---|---|---|---|
| OpenAI-compatible | `POST {base}/chat/completions` (base default `https://api.openai.com/v1`) | `Authorization: Bearer` | SSE `chat.completion.chunk`, `choices[].delta.content`, final `usage` when `stream_options:{"include_usage":true}` |
| Gemini | `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse` (+ `:generateContent`) | `x-goog-api-key` header | SSE `candidates[0].content.parts[].text`, `usageMetadata`, `error.code/message` |
| Anthropic | `POST https://api.anthropic.com/v1/messages` with `"stream":true` | `x-api-key` + `anthropic-version: 2023-06-01` | SSE `message_start`(usage.in) → `content_block_delta`(text_delta/thinking_delta) → `message_delta`(usage.out) → `message_stop`; `event: error` |

- Model listing: OpenAI `GET {base}/models` (data[].id); Gemini `GET /v1beta/models?pageSize=200` (filter `supportedGenerationMethods ∋ generateContent`, parse `inputTokenLimit`); Anthropic `GET /v1/models` (data[].id/display_name).
- Streaming events parsed by `AnthropicSseEventParser` (typed event+data pairs) and OpenAI/Gemini `data:`-only parsing.
- Capability flags per provider; non-streaming path emits `Started → TextDelta(full) → Completed` (clearly not tokenized).

## 7. Credential Architecture

- `SecureCredentialStore` (Phase 1, untouched interface) stores API keys under alias `ai_credential_<uuid>`.
- `AiCredentialManager`: store/get/delete/list by reference; `resolveKey(credentialId)` used ONLY inside adapters at request time; never returned to UI; `ProviderConfig` rows hold only `credentialId`.
- Key input UI: password field, temporary show/hide, Test Connection before save, Save → store → keep only reference; "Configured" badge (never echoes the key); Clear/Replace with confirmation; deletion removes keystore entry first, then config row (no orphans).

## 8. Persistence (Room v3)

New entities (all metadata, no secrets):
- `AIProviderConfigEntity(providerId PK, displayName, enabled, credentialId?, baseUrlOverride?, defaultModelId?, createdAt, updatedAt, lastConnectionCheckAt?, lastConnectionStatus?)`
- `AIModelCacheEntity(providerId+modelId PK, displayName, contextWindow?, capabilitiesCsv, inputPricing?, outputPricing?, enabled, lastUpdated)`
- `AIUsageRecordEntity(id PK, conversationId?, messageId?, providerId, modelId, inputTokens?, outputTokens?, totalTokens?, cachedTokens?, reasoningTokens?, estimatedCostUsd?, createdAt)` — cost stored only when usage AND verified pricing both present, else null.
- `AISettingsEntity(id=1, defaultProviderId?, defaultModelId?, streamingEnabled=true, showUsage=true, showEstimatedCost=true, saveFailedRequests=true, connectTimeoutSeconds=15, readTimeoutSeconds=120, retryCount=2, maxPayloadChars=128_000)`

DAOs: `AIProviderConfigDao`, `AIModelCacheDao`, `AIUsageRecordDao`, `AISettingsDao`.
Migration 2→3: `CREATE TABLE IF NOT EXISTS ...` + indices; no destructive fallback (remove `fallbackToDestructiveMigration` and ship real migration so existing Phase 1–4 user data survives).
Conversation table gains `providerId`, `modelId`, `editTitle` support via existing `ConversationRepository` (per-conversation selection; falls back to AI settings default).

## 9. Conversation/Chat Integration

- Extend (not duplicate) `ConversationRepository`: `setConversationModel(providerId, modelId)`, `updateTitle`.
- `ChatOrchestrator` (new, in `core/ai`): send user msg → build `AIRequest` from persisted history + per-conversation model (fallback: default provider/model from `AISettingsEntity`) → stream events → upsert assistant message batched (throttled ~500ms + final) → persist `AIUsageRecord` → delete recovery only, never duplicates → cancel via `Job.cancel()` propagating to OkHttp call.
- Error mapping: `AIErrorMapper` HTTP code/status → normalized `AIError`; failed sends store assistant error message only if "Save failed requests" enabled (with `errorState` metadata, no raw payloads).

## 10. UI

- Navigation: `Screen.AiProviders("ai_providers")`, `Screen.AiSettings("ai_settings")` (non-bottom-nav destinations).
- `AiProvidersScreen`: list of 3 provider cards (OpenAI/Gemini/Anthropic) with status (✓ Connected / ○ Not configured / disabled), default model, last check time; `+ Add Provider` → detail.
- `ProviderDetailScreen`: credential state ("Configured"/"Not set"), API key input (masked), Test Connection, Save, Refresh Models, model dropdown/list, default model picker, Enable/Disable, Remove Configuration (confirm; deletes keystore secret then row), capabilities list, base URL (HTTPS-validated, advanced/collapsible).
- `AiSettingsScreen` (from Settings): default provider/model, streaming toggle, timeout, retry count, show usage/cost, save-failed toggle.
- `ConversationDetailScreen` upgrade: provider/model selector chips in top bar, streaming bubbles with progressive text, Stop button while streaming, Retry on failed assistant turn, Copy response, New conversation; context-size estimate warning (>80% of model context ⇒ warn; >100% ⇒ block) labeled "estimate".
- SettingsScreen: replace "AI Model Providers" placeholder row with real navigation (Phase 5 implemented).

## 11. Error/Timeout/Retry (summary)

- `AIErrorMapper`: 401/400-ish → Authentication/InvalidRequest; 403 → Authorization; 404 → ModelNotFound; 408/TimeoutIOException → Timeout; 429 (+Retry-After) → RateLimit; 5xx → ServerError; IOException → Network; connectivity check pre-request → ProviderUnavailable ("No internet connection."); CancellationException → Cancelled (no retry, no error UI besides cancel state).
- All timeouts bounded; retry never applies to auth/validation errors; max 2 retries, backoff 0.5s→1s→2s (capped 4s), Retry-After respected.

## 12. Security Checklist (design-time enforcement)

- Keys: keystore-only; Room stores `credentialId` reference; never in BuildConfig/source/logs/UI state/exports.
- UI shows "Configured" only; no partial keys.
- OkHttp logging BASIC + redaction interceptor; no bodies; no prompts logged by default.
- HTTPS enforced by `HttpsUrlValidator` (http:// rejected; loopback only behind explicit debug flag, default OFF, never for saved configs).
- Adapters receive no engine/filesystem dependencies → cannot touch files/terminal/Linux.
- Secure delete flow: keystore delete → DB row delete.

## 13. Testing

Pure-JVM unit tests (JUint4, existing convention):
- `SseLineParserTest` — data/event fields, [DONE], multiline, CRLF.
- `RetryPolicyTest` — transient-only retry, backoff caps, no retry on auth/4xx, Retry-After.
- `AIErrorMapperTest` — code mapping table.
- `OpenAICompatibleProviderTest`, `GeminiProviderTest`, `AnthropicProviderTest` — MockWebServer: streaming deltas, usage parsing, non-streaming, error mapping (401→Authentication, 429→RateLimit), model listing.
- `FakeAIProviderTest` — deterministic scripted events; FakeAIProvider lives in `src/test` only (never registered in prod DI).
- `AiSettingsRepositoryTest`, `AiProviderConfigRepositoryTest` — Room in-memory (Robolectric not available; use plain JUnit + Room runtime? → use `room-testing` with in-memory DB under Robolectric-free JVM via `Room.inMemoryDatabaseBuilder` requires Android; instead test DAOs via instrumented-style JVM fakes) → Final: repository logic tested against **in-memory fake DAOs** to stay pure JVM, mirroring existing test suite style.
- `AIProviderManagerTest` — registration/lookup/enable-disable/default resolution with fakes.
- `HttpsUrlValidatorTest` — HTTPS enforcement, invalid URL rejection.
- Context estimation: `ChatOrchestratorTest` (context-limit estimation math with fake provider).

No live API keys anywhere in tests (spec §52/§53).

## 14. Build & Verification

- `./gradlew compileDebugKotlin`, `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` — all must pass.
- Phase 1–4 regression: existing 18 suites must keep passing; no schema breaks (migration 2→3 additive).

## 15. Future (Phase 6+) compatibility

- `AIProviderManager` is injectable into a future `AgentEngine` without UI changes; providers expose `capabilities` so agent can ask before tool use (still disabled in Phase 5).
- `AIRequest.metadata`/`AIMessage.metadata` reserved for future tool/vision payloads.
- Local-model provider possible by implementing `AIProvider` with `baseUrl=http://127.0.0.1` + explicit local flag (design accommodates; NOT implemented now).
