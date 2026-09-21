package com.devstation.android.core.security.policy

/**
 * Phase 7 §20–§23: network policy.
 *
 * Phase 6 classified *commands* as NETWORK. Phase 7 additionally looks at the destination the
 * command names, because "npm install" and "curl http://127.0.0.1:8080/health" have very different
 * exposure. Localhost is explicitly NOT treated as harmless (§23) — it has its own class.
 */
class NetworkSecurityPolicy {

    data class NetworkDestination(
        val intent: NetworkIntent,
        val host: String? = null,
        val port: Int? = null,
        val scheme: String? = null,
        /** Safe to display/log: user info and query string removed (§22/§35). */
        val display: String? = null
    )

    /** Classify the network intent implied by [command] and its arguments. */
    fun destination(command: String): NetworkDestination = analyze(command)

    /**
     * True when the command is expected to reach a network destination. Command *names* alone are
     * never enough: `git fetch` and `curl` do, `npm run build` may or may not.
     */
    fun impliesNetwork(command: String): Boolean = analyze(command).intent != NetworkIntent.NONE

    /** Remove credentials and query parameters from a URL so it is safe to show and store. */
    fun redact(url: String): String {
        var value = url.trim()
        if (value.isEmpty()) return value
        value = value.substringBefore('#')
        value = value.substringBefore('?')
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) {
            val scheme = value.substring(0, schemeIndex + 3)
            val rest = value.substring(schemeIndex + 3)
            val at = rest.indexOf('@')
            val cleaned = if (at >= 0) rest.substring(at + 1) else rest
            value = scheme + cleaned
        }
        return if (value.length > MAX_DISPLAY_CHARS) value.take(MAX_DISPLAY_CHARS) + "…" else value
    }

    private fun analyze(command: String): NetworkDestination {
        val tokens = command.split(Regex("\\s+")).map { it.trim('"', '\'', '`', ',', ')') }.filter { it.isNotEmpty() }

        // 1. An explicit URL is unambiguous and is classified wherever it appears.
        for (token in tokens) {
            val url = URL_PATTERN.find(token)?.value
            if (url != null) {
                val host = hostOf(url)
                val port = portOf(url)
                val scheme = url.substringBefore("://").lowercase().ifEmpty { null }
                return NetworkDestination(
                    intent = classifyHost(host),
                    host = host,
                    port = port,
                    scheme = scheme,
                    display = redact(url)
                )
            }
        }

        // 2. A bare hostname/IP token is only evidence of network use when the command itself is a
        //    network command. `cat config.txt` names a file, not a host, even though it parses as a
        //    hostname — treating it as one would demand approval for every ordinary file read.
        val networkOriented = NETWORK_COMMANDS.any { command.contains(it) }
        if (networkOriented) {
            for (token in tokens) {
                val hostToken = HOST_PATTERN.find(token) ?: continue
                val host = hostToken.value.substringBefore('/').substringBefore(':').lowercase()
                val port = hostToken.groupValues.getOrNull(1)?.toIntOrNull()
                return NetworkDestination(
                    intent = classifyHost(host),
                    host = host,
                    port = port,
                    display = redact(host + (port?.let { ":$it" } ?: ""))
                )
            }
            for (token in tokens) {
                val ipToken = IP_PATTERN.find(token) ?: continue
                val host = ipToken.groupValues[1]
                val port = ipToken.groupValues[2].toIntOrNull()
                return NetworkDestination(
                    intent = classifyHost(host),
                    host = host,
                    port = port,
                    display = redact(host + (port?.let { ":$it" } ?: ""))
                )
            }
        }

        // 3. A local development server binds a port and accepts connections: local, not harmless.
        if (isLocalServer(command)) {
            return NetworkDestination(intent = NetworkIntent.LOCAL_NETWORK, port = portHint(command))
        }

        // 4. Package managers and the like reach a registry even without an explicit URL, so the
        //    intent is INTERNET (with no displayable destination) rather than "no network".
        if (networkOriented) {
            return NetworkDestination(intent = NetworkIntent.INTERNET)
        }

        return NetworkDestination(intent = NetworkIntent.NONE, port = portHint(command))
    }

    /** Port the command intends to bind/listen on, when one is discoverable (§24). */
    fun portHint(command: String): Int? {
        PORT_FLAG_PATTERN.find(command)?.let { return it.groupValues[1].toIntOrNull() }
        ENV_PORT_PATTERN.find(command)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** True when the command looks like it starts a long-running local server. */
    fun isLocalServer(command: String): Boolean {
        val lower = command.lowercase()
        return SERVER_HINTS.any { lower.contains(it) }
    }

    fun classifyHost(host: String?): NetworkIntent {
        val value = host?.lowercase()?.trim('[', ']') ?: return NetworkIntent.NONE
        if (value.isEmpty()) return NetworkIntent.NONE
        if (value == "localhost" || value.endsWith(".localhost") || value.endsWith(".local")) {
            return NetworkIntent.LOCAL_NETWORK
        }
        if (value == "::1" || value == "0.0.0.0") return NetworkIntent.LOCAL_NETWORK
        if (PRIVATE_IPV4.containsMatchIn(value) || PRIVATE_IPV6.containsMatchIn(value)) {
            return NetworkIntent.LOCAL_NETWORK
        }
        return NetworkIntent.INTERNET
    }

    /** Human explanation used in approval cards and audit events. */
    fun describe(destination: NetworkDestination): String = when (destination.intent) {
        NetworkIntent.INTERNET ->
            "This command can reach the network" +
                (destination.display?.let { " ($it)" } ?: "") +
                ". It may download code or send data to an external server."
        NetworkIntent.LOCAL_NETWORK ->
            "This command uses a local/private network address" +
                (destination.display?.let { " ($it)" } ?: "") +
                ". Local services can still expose ports and read project files."
        NetworkIntent.NONE -> "No network destination was detected for this command."
    }

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val withoutUserInfo = authority.substringAfter('@')
        if (withoutUserInfo.startsWith("[")) return withoutUserInfo.substringBefore(']').removePrefix("[")
        return withoutUserInfo.substringBefore(':').ifEmpty { null }
    }

    private fun portOf(url: String): Int? {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringAfter('@')
        if (authority.startsWith("[")) {
            return authority.substringAfter("]:", "").takeWhile { it.isDigit() }.toIntOrNull()
        }
        if (!authority.contains(':')) return null
        return authority.substringAfter(':').takeWhile { it.isDigit() }.toIntOrNull()
    }

    private companion object {
        const val MAX_DISPLAY_CHARS = 120

        val URL_PATTERN = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"']+""")
        val HOST_PATTERN = Regex("""^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?::(\d{1,5}))?(?:/\S*)?$""")
        val IP_PATTERN = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(?:/\S*)?$""")
        val PORT_FLAG_PATTERN = Regex("""(?:--port[=\s]+|-p\s+|:)(\d{2,5})\b""")
        val ENV_PORT_PATTERN = Regex("""\bPORT=(\d{2,5})\b""")
        val PRIVATE_IPV4 = Regex("""^(?:10\.|127\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)""")
        val PRIVATE_IPV6 = Regex("""^(?:fe80:|fc|fd|::1)""")

        val NETWORK_COMMANDS = listOf(
            "curl ", "wget ", "git clone", "git fetch", "git pull", "npm install", "npm i ",
            "yarn add", "pnpm add", "pip install", "pip3 install", "apk add", "apt install",
            "apt-get install", "nc ", "ncat ", "ssh ", "scp ", "ping ", "sftp "
        )

        val SERVER_HINTS = listOf(
            "npm run dev", "npm start", "yarn dev", "pnpm dev", "vite", "next dev",
            "python -m http.server", "python3 -m http.server", "flask run", "uvicorn",
            "node server", "serve ", "http-server", "gradle bootrun"
        )
    }
}
