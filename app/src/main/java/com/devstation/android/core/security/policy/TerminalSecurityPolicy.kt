package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.CommandCategory
import com.devstation.android.core.agent.CommandClassification
import com.devstation.android.core.agent.CommandClassifier
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.security.policy.NetworkSecurityPolicy.NetworkDestination
import java.io.File

/**
 * Phase 7 §15–§19: terminal policy.
 *
 * The command *name* is never the whole story. This policy combines
 * [CommandClassifier] (category + risk), argument inspection (paths the command references) and
 * [NetworkSecurityPolicy] (destination + port). It never denies by keyword: it either classifies,
 * or it blocks for a concrete structural reason (an Android-private path, a command substitution
 * that cannot be verified, an escape from the workspace in the fallback shell).
 */
class TerminalSecurityPolicy(
    private val network: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    private val sensitiveFiles: SensitiveFilePolicy = SensitiveFilePolicy()
) {

    data class Assessment(
        val category: CommandCategory,
        val risk: ToolRiskLevel,
        val reason: String,
        val compound: Boolean,
        val networkIntent: NetworkIntent,
        val destination: NetworkDestination?,
        val port: Int?,
        val localServer: Boolean,
        /** The command references a file that normally holds secrets (§11/§12). */
        val sensitiveArgument: Boolean,
        /** Non-null when the command must not run at all. */
        val blockedReason: String? = null
    ) {
        val classification: CommandClassification get() = CommandClassification(category, risk, reason, compound)
        val requiresNetworkApproval: Boolean get() = networkIntent != NetworkIntent.NONE
    }

    /**
     * @param root the project root, used to decide whether an absolute path is inside the project.
     * @param guest true when the command runs inside the Linux guest (`/workspace`), false for the
     *        restricted Android shell fallback where host paths are real Android paths.
     */
    fun assess(command: String, root: File?, guest: Boolean = true): Assessment {
        val base = CommandClassifier.classify(command)
        val destination = network.destination(command)
        val port = destination.port ?: network.portHint(command)
        val localServer = network.isLocalServer(command)

        var category = base.category
        var risk = base.riskLevel
        var reason = base.reason

        // §23: a reachable local/private address is its own class, not a harmless one.
        var networkIntent = when {
            destination.intent != NetworkIntent.NONE -> destination.intent
            base.category == CommandCategory.NETWORK -> NetworkIntent.INTERNET
            else -> NetworkIntent.NONE
        }
        if (base.category == CommandCategory.NETWORK && destination.intent == NetworkIntent.LOCAL_NETWORK) {
            category = CommandCategory.LOCAL_NETWORK
            reason = "'${destination.host}' is a local/private address; local services are not harmless"
        }
        // §24: a command that starts a listening server also opens a local socket.
        if (localServer && RANKS[category]!! < RANKS.getValue(CommandCategory.LOCAL_NETWORK)) {
            category = CommandCategory.LOCAL_NETWORK
            networkIntent = NetworkIntent.LOCAL_NETWORK
            reason = "starts a local server, which binds a port on this device"
        }

        val inspection = inspectArguments(command, root, guest)
        if (inspection.blockedReason != null) {
            return Assessment(
                category = CommandCategory.SYSTEM,
                risk = ToolRiskLevel.CRITICAL,
                reason = inspection.blockedReason,
                compound = base.compound,
                networkIntent = networkIntent,
                destination = destination,
                port = port,
                localServer = localServer,
                sensitiveArgument = false,
                blockedReason = inspection.blockedReason
            )
        }

        // Unverifiable shell constructs are always-ask, never auto-allowed. A stricter existing
        // category (destructive, system, package removal) is never weakened by this.
        if (inspection.unverifiable && RANKS[category]!! < RANKS[CommandCategory.UNKNOWN]!!) {
            category = CommandCategory.UNKNOWN
            risk = ToolRiskLevel.HIGH
            reason = "command uses shell substitution that cannot be verified"
        }

        // A redirect writes to the project, so it can never be read-only.
        if (inspection.redirects && RANKS[category]!! < RANKS[CommandCategory.MODIFY_PROJECT]!!) {
            category = CommandCategory.MODIFY_PROJECT
            risk = ToolRiskLevel.MEDIUM
            reason = "command redirects output, which writes to the filesystem"
        }

        // A sensitive argument does not change the category; the engine raises the required
        // permission to at least ASK and reports the sensitive resource to the user.
        if (inspection.sensitiveArgument) {
            risk = if (RISK_RANKS.getValue(risk) < RISK_RANKS.getValue(ToolRiskLevel.MEDIUM)) {
                ToolRiskLevel.MEDIUM
            } else {
                risk
            }
        }

        return Assessment(
            category = category,
            risk = risk,
            reason = reason,
            compound = base.compound || inspection.redirects,
            networkIntent = networkIntent,
            destination = destination,
            port = port,
            localServer = localServer,
            sensitiveArgument = inspection.sensitiveArgument
        )
    }

    /** Permission the assessment requires before any user policy is applied. */
    fun permissionFor(assessment: Assessment): ToolPermission =
        if (assessment.blockedReason != null) {
            ToolPermission.DENY
        } else {
            assessment.classification.defaultPermission()
        }

    data class ArgumentInspection(
        val sensitiveArgument: Boolean = false,
        val unverifiable: Boolean = false,
        val redirects: Boolean = false,
        val blockedReason: String? = null
    )

    fun inspectArguments(command: String, root: File?, guest: Boolean): ArgumentInspection {
        val unverifiable = UNVERIFIABLE.any { command.contains(it) }
        val redirects = REDIRECT_PATTERN.containsMatchIn(command)
        var sensitive = false

        for (token in tokensOf(command)) {
            // Sensitive filenames are flagged even without a path separator (`cat .env`).
            if (sensitiveFiles.isSensitivePath(token.substringAfterLast('/'))) sensitive = true

            if (!looksLikePath(token)) continue

            val normalized = normalizePath(token)

            val blockedPrefix = HOST_PRIVATE_PREFIXES.firstOrNull {
                normalized == it || normalized.startsWith("$it/")
            }
            if (blockedPrefix != null) {
                return ArgumentInspection(
                    blockedReason = "Blocked: the command references '$blockedPrefix', which is " +
                        "Android system or app-private storage."
                )
            }

            if (PROC_LEAK_PATTERN.containsMatchIn(normalized)) {
                return ArgumentInspection(
                    blockedReason = "Blocked: the command tries to read process environment/" +
                        "memory through $normalized."
                )
            }

            // §17: in the Android shell fallback, host paths are real Android paths, so every
            // path-like argument — relative or absolute — must resolve inside the project.
            if (!guest && root != null) {
                val candidate = if (normalized.startsWith("/")) File(normalized) else File(root, token)
                val target = runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
                val rootPath = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
                val targetNorm = target.path.replace('\\', '/')
                val rootNorm = rootPath.path.replace('\\', '/')
                if (targetNorm != rootNorm &&
                    !targetNorm.startsWith(rootNorm.trimEnd('/') + "/")
                ) {
                    return ArgumentInspection(
                        blockedReason = "Blocked: '$token' is outside the selected project. The " +
                            "fallback shell can only work inside the project."
                    )
                }
            }

        }

        return ArgumentInspection(sensitiveArgument = sensitive, unverifiable = unverifiable, redirects = redirects)
    }

    private fun tokensOf(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null -> if (ch == quote) quote = null else current.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() || ch in DELIMITERS -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun looksLikePath(token: String): Boolean =
        token.startsWith("/") ||
            token.startsWith("./") ||
            token.startsWith("../") ||
            token.startsWith("file://") ||
            token.startsWith("~") ||
            token.contains('/')

    private fun normalizePath(token: String): String {
        val withoutScheme = token.removePrefix("file://").replace('\\', '/')
        if (!withoutScheme.startsWith("/")) {
            return withoutScheme.lowercase()
        }
        val segments = mutableListOf<String>()
        withoutScheme.split('/').filter { it.isNotEmpty() && it != "." }.forEach { segment ->
            if (segment == "..") segments.removeLastOrNull() else segments.add(segment)
        }
        return "/" + segments.joinToString("/").lowercase()
    }

    private companion object {
        val RANKS = mapOf(
            CommandCategory.READ_ONLY to 0,
            CommandCategory.MODIFY_PROJECT to 1,
            CommandCategory.UNKNOWN to 2,
            CommandCategory.LOCAL_NETWORK to 3,
            CommandCategory.INSTALL_PACKAGE to 3,
            CommandCategory.NETWORK to 4,
            CommandCategory.PACKAGE_REMOVE to 5,
            CommandCategory.SYSTEM to 6,
            CommandCategory.DESTRUCTIVE to 7
        )

        val RISK_RANKS = mapOf(
            ToolRiskLevel.LOW to 0,
            ToolRiskLevel.MEDIUM to 1,
            ToolRiskLevel.HIGH to 2,
            ToolRiskLevel.CRITICAL to 3
        )

        /** Android/system locations that are never a legitimate agent argument. */
        val HOST_PRIVATE_PREFIXES = listOf(
            "/data", "/sdcard", "/storage", "/mnt/sdcard", "/system", "/vendor", "/apex", "/root"
        )

        val PROC_LEAK_PATTERN = Regex("""^/proc/(?:self|\d+)/(?:environ|cmdline|mem|maps)$""")

        /** Shell substitution makes a command unverifiable, so it always needs approval (§16). */
        val UNVERIFIABLE = listOf("$(", "`", "${'$'}{")

        val REDIRECT_PATTERN = Regex("""(?:^|\s)(?:>>?|tee\s)""")

        val DELIMITERS = setOf(';', '|', '&', '<', '>', '(', ')', ',')
    }
}
