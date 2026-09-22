# Phase 8 Implementation Plan
# MCP + Skills + Custom Agents

## 1. Executive Summary

Phase 8 adds three new capability layers on top of the existing Phase 1–7 architecture:

1. **MCP Client Infrastructure** — Secure protocol client for external tool/resource/prompt providers
2. **Skills System** — Reusable AI workflow definitions (built-in + custom)
3. **Custom Agent Profiles** — Configurable AI execution profiles

All three layers execute through the existing `ToolExecutor → SecurityPolicyEngine → PermissionEvaluator → Revalidation → Audit` pipeline. No capability can bypass security.

## 2. Architecture Overview

```
AI Provider → AgentRuntime → Agent Profile → Skill Engine → MCP Client
                                                                ↓
                                                          MCP Server Connection
                                                                ↓
                                                        SecurityManager (mandatory)
                                                                ↓
                                                        SecurityPolicyEngine
                                                                ↓
                                                        PermissionEvaluator
                                                                ↓
                                                        Approval / Deny
                                                                ↓
                                                        Revalidation
                                                                ↓
                                                        Tool Execution
                                                                ↓
                                                        SecurityAudit
```

## 3. MCP Client Infrastructure

### 3.1 Package Location
`core/mcp/`

### 3.2 Components

| Component | Responsibility |
|-----------|---------------|
| `McpClient` | Protocol client — connect, initialize, send requests, receive responses |
| `McpServerManager` | Registry of configured MCP servers, lifecycle management |
| `McpTransport` | Transport abstraction (STDIO, HTTP) |
| `McpSession` | Active connection to one server |
| `McpCapabilityRegistry` | Discovered tools/resources/prompts from all connected servers |
| `McpToolWrapper` | Wraps an MCP tool as a DevStation `Tool` for the existing registry |
| `McpModels` | All MCP data models |

### 3.3 Transport Abstraction
```kotlin
interface McpTransport {
    suspend fun connect(config: McpServerConfig): McpConnection
    suspend fun send(connection: McpConnection, request: McpJsonRpcRequest): McpJsonRpcResponse
    suspend fun close(connection: McpConnection)
}
```

Two implementations:
- `StdioMcpTransport` — launches server process via the existing secure process infrastructure
- `HttpMcpTransport` — HTTP POST to SSE endpoint (future-ready, minimal impl)

### 3.4 Server Configuration
Persisted in Room. No raw secrets — credential references only.

### 3.5 Security Integration
- MCP tool execution → `ToolExecutor` → `SecurityPolicyEngine` (mandatory)
- MCP tools classified into existing security categories (READ_ONLY, PROJECT_WRITE, etc.)
- Unknown MCP capabilities default to UNKNOWN → always-ask
- MCP output bounded by `OutputLimiter`
- MCP prompts treated as untrusted content
- MCP resources validated by scheme + sandbox

## 4. Skills System

### 4.1 Package Location
`core/skills/`

### 4.2 Components

| Component | Responsibility |
|-----------|---------------|
| `SkillDefinition` | Structured skill metadata + instructions |
| `SkillManager` | Registry, validation, lifecycle |
| `SkillExecutor` | Runs a skill through AgentRuntime + ToolRegistry |
| `SkillValidator` | Schema + security validation before registration |
| `SkillModels` | All skill data models |

### 4.3 Skill Format
```kotlin
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val instructions: String,
    val requiredTools: List<String>,
    val requestedCapabilities: List<SkillCapability>,
    val securityProfile: SkillSecurityProfile,
    val source: SkillSource // BUILTIN, PROJECT, USER
)
```

### 4.4 Security Rules
- Skill execution goes through AgentRuntime → ToolExecutor → SecurityManager
- Skills cannot declare `security.unrestricted=true`
- Skills cannot grant permissions or modify security policy
- Recursion protection: max depth 3, loop detection via skill ID stack
- Skills are validated before registration (schema + capability + tool references)

### 4.5 Built-in Skills
1. Code Review
2. Explain Code  
3. Fix Compile Error
4. Refactor Code
5. Generate Tests
6. Project Search
7. Documentation Writer
8. README Generator

## 5. Custom Agent Profiles

### 5.1 Package Location
`core/agent/profiles/`

### 5.2 Agent Profile Model
```kotlin
data class AgentProfile(
    val id: String,
    val name: String,
    val description: String,
    val systemInstructions: String,
    val providerId: String?,
    val modelId: String?,
    val enabledTools: List<String>,
    val enabledSkills: List<String>,
    val enabledMcpServers: List<String>,
    val permissionProfile: AgentProfilePermissions,
    val projectScope: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 5.3 Security Rules
- Profiles are configuration, not security authorities
- Cannot grant permissions, disable security, or modify SecurityPolicyEngine
- Cannot override always-deny resources
- Invalid security capability declarations → rejected with explanation
- Profile execution uses existing AgentRuntime

## 6. Database Migration (v5 → v6)

Additive only. New tables:

- `mcp_servers` — server configurations
- `mcp_capabilities` — discovered capabilities
- `skills` — skill definitions  
- `agent_profiles` — custom agent profiles

All Phase 1–7 data untouched.

## 7. UI Screens

| Screen | Route | Description |
|--------|-------|-------------|
| MCP Servers | `mcp_servers` | List + manage MCP servers |
| MCP Server Detail | `mcp_server/{serverId}` | Server info, capabilities, security |
| Skills | `skills` | Built-in + custom skills |
| Skill Detail | `skill/{skillId}` | Skill info, run, edit |
| Agent Profiles | `agent_profiles` | Custom agent profiles |
| Agent Builder | `agent_builder/{profileId?}` | Create/edit agent profiles |

## 8. Test Strategy

### MCP Tests (30+)
Server registration, transport, capability discovery, tool execution, security integration, output limits, cancellation, process isolation, network policy, prompt injection, path traversal, credential access

### Skills Tests (20+)
Validation, execution, recursion protection, timeout, cancellation, permission model, prompt injection, project isolation, audit events

### Agent Profile Tests (20+)
CRUD, provider/model selection, tools/skills/MCP integration, security policy modification attempts, permission self-grant attempts, tool execution, cancellation

### Security Attack Tests (20+)
MCP path traversal, credential access, skill permission escalation, agent security bypass, approval replay

### Regression
All 373 Phase 1–7 tests must continue passing.

## 9. Files to Create

### Source Files
```
core/mcp/McpModels.kt
core/mcp/McpTransport.kt
core/mcp/McpClient.kt
core/mcp/McpServerManager.kt
core/mcp/McpSession.kt
core/mcp/McpCapabilityRegistry.kt
core/mcp/McpToolWrapper.kt
core/mcp/StdioMcpTransport.kt
core/skills/SkillModels.kt
core/skills/SkillValidator.kt
core/skills/SkillManager.kt
core/skills/SkillExecutor.kt
core/skills/BuiltInSkills.kt
core/agent/profiles/AgentProfileModels.kt
core/agent/profiles/AgentProfileManager.kt
core/database/McpEntities.kt
core/database/SkillEntities.kt
core/database/AgentProfileEntities.kt
feature/mcp/McpServersScreen.kt
feature/mcp/McpServerDetailScreen.kt
feature/mcp/McpViewModel.kt
feature/skills/SkillsScreen.kt
feature/skills/SkillDetailScreen.kt
feature/skills/SkillsViewModel.kt
feature/agentprofiles/AgentProfilesScreen.kt
feature/agentprofiles/AgentBuilderScreen.kt
feature/agentprofiles/AgentProfilesViewModel.kt
```

### Modified Files
```
core/database/DevStationDatabase.kt (v5→v6 migration)
core/di/AppContainer.kt (DI wiring)
navigation/Screen.kt (new routes)
navigation/DevStationNavGraph.kt (new destinations)
feature/settings/SettingsScreen.kt (new entries)
core/security/policy/SecurityAudit.kt (new event types)
core/security/policy/SecurityDiagnostics.kt (extended checks)
```

### Test Files
```
test/.../core/mcp/McpClientTest.kt
test/.../core/mcp/McpServerManagerTest.kt
test/.../core/mcp/McpTransportTest.kt
test/.../core/mcp/McpSecurityTest.kt
test/.../core/skills/SkillValidatorTest.kt
test/.../core/skills/SkillManagerTest.kt
test/.../core/skills/SkillExecutorTest.kt
test/.../core/skills/BuiltInSkillsTest.kt
test/.../core/agent/profiles/AgentProfileManagerTest.kt
test/.../core/security/Phase8SecurityAttackTest.kt
```
