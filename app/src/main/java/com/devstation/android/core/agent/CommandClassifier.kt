package com.devstation.android.core.agent

/**
 * Command categories. DevStation does NOT use a keyword blacklist to *deny* commands — it uses
 * this classification to decide which permission the user's policy requires.
 *
 * Phase 7 adds [PACKAGE_REMOVE], [SYSTEM], [LOCAL_NETWORK] and [UNKNOWN]. [MODIFY_PROJECT] is the
 * project-write category; [UNKNOWN] applies when the command cannot be recognised safely, and
 * requires an always-ask approval instead of being assumed safe.
 */
enum class CommandCategory {
    READ_ONLY,
    MODIFY_PROJECT,
    LOCAL_NETWORK,
    INSTALL_PACKAGE,
    PACKAGE_REMOVE,
    NETWORK,
    SYSTEM,
    UNKNOWN,
    DESTRUCTIVE
}

data class CommandClassification(
    val category: CommandCategory,
    val riskLevel: ToolRiskLevel,
    val reason: String,
    /** True when the command chains multiple segments or uses shell metacharacters. */
    val compound: Boolean = false
) {
    fun defaultPermission(): ToolPermission = when (category) {
        CommandCategory.READ_ONLY -> ToolPermission.ALLOW
        CommandCategory.MODIFY_PROJECT -> ToolPermission.ASK
        CommandCategory.LOCAL_NETWORK -> ToolPermission.ASK
        CommandCategory.INSTALL_PACKAGE -> ToolPermission.ASK
        CommandCategory.PACKAGE_REMOVE -> ToolPermission.ALWAYS_ASK
        CommandCategory.NETWORK -> ToolPermission.ASK
        CommandCategory.SYSTEM -> ToolPermission.ALWAYS_ASK
        // Unrecognised commands are never assumed safe.
        CommandCategory.UNKNOWN -> ToolPermission.ALWAYS_ASK
        CommandCategory.DESTRUCTIVE -> ToolPermission.ALWAYS_ASK
    }
}

/**
 * Static, testable classification of a shell command string.
 *
 * The whole command is treated as untrusted input: it is tokenized (not executed) and each
 * segment is classified; the most dangerous segment wins. Commands the classifier does not
 * recognize are treated as project-modifying and therefore require approval.
 */
object CommandClassifier {

    fun classify(rawCommand: String): CommandClassification {
        val command = rawCommand.trim()
        if (command.isEmpty()) {
            return CommandClassification(CommandCategory.READ_ONLY, ToolRiskLevel.LOW, "Empty command")
        }

        val segments = splitSegments(command)
        val compound = segments.size > 1 || hasMetacharacters(command)

        var worst: CommandClassification? = null
        for (segment in segments) {
            val classification = classifySegment(segment)
            if (worst == null || rank(classification.category) > rank(worst.category)) {
                worst = classification
            }
        }
        val base = worst ?: CommandClassification(CommandCategory.UNKNOWN, ToolRiskLevel.HIGH, "Unrecognized command")
        return base.copy(compound = compound || base.compound)
    }

    private fun rank(category: CommandCategory): Int = when (category) {
        CommandCategory.READ_ONLY -> 0
        CommandCategory.MODIFY_PROJECT -> 1
        CommandCategory.UNKNOWN -> 2
        CommandCategory.LOCAL_NETWORK -> 3
        CommandCategory.INSTALL_PACKAGE -> 3
        CommandCategory.NETWORK -> 4
        CommandCategory.PACKAGE_REMOVE -> 5
        CommandCategory.SYSTEM -> 6
        CommandCategory.DESTRUCTIVE -> 7
    }

    private fun classifySegment(tokens: List<String>): CommandClassification {
        var effective = tokens
        // `sudo`/`env` prefixes do not change classification, but keep the real command visible.
        while (effective.isNotEmpty() && effective.first() in PREFIXES) {
            effective = effective.drop(1)
        }
        if (effective.isEmpty()) {
            // A bare privilege prefix (`sudo`, `su`) is not a no-op — it must be approved.
            return CommandClassification(
                CommandCategory.SYSTEM,
                ToolRiskLevel.HIGH,
                "Privilege prefix without a command"
            )
        }

        val base = effective.first().substringAfterLast('/').lowercase()
        val args = effective.drop(1)
        val sub = args.firstOrNull()?.lowercase()?.trimStart('-') ?: ""
        val allArgs = args.joinToString(" ").lowercase()

        return when (base) {
            "rm", "rmdir", "shred", "dd", "mkfs", "fdisk", "wipefs", "truncate", "kill", "killall", "pkill", "reboot", "shutdown" ->
                destructive(base)

            "apk" -> when (sub) {
                "add" -> install(base)
                "del", "remove" -> packageRemove("apk del")
                "update", "upgrade" -> network(base)
                else -> readonly(base)
            }

            "npm", "yarn", "pnpm", "bun" -> when (sub) {
                "install", "i", "add", "ci" -> install("$base $sub")
                "uninstall", "remove", "rm" -> packageRemove("$base $sub")
                "run", "test", "exec", "start", "dev", "serve" -> modify(base)
                else -> modify(base)
            }

            "pip", "pip3" -> when (sub) {
                "install" -> install("$base install")
                "uninstall" -> packageRemove("$base uninstall")
                else -> readonly(base)
            }

            "apt", "apt-get", "dpkg" -> when (sub) {
                "install" -> install("$base install")
                "remove", "purge", "-r", "--remove", "-p", "--purge" -> packageRemove("$base $sub")
                "update", "upgrade" -> network(base)
                else -> readonly(base)
            }

            "mount", "umount", "insmod", "rmmod", "modprobe", "sysctl", "chroot", "setenforce",
            "systemctl", "service", "setprop", "su", "doas" ->
                system(base)

            "curl", "wget", "nc", "ncat", "ssh", "scp", "sftp", "rsync", "ping", "telnet", "ftp" ->
                network(base)

            "git" -> classifyGit(sub, allArgs)

            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "find", "pwd", "echo",
            "which", "whoami", "id", "wc", "stat", "file", "du", "df", "tree", "less", "more",
            "sort", "uniq", "diff", "basename", "dirname", "realpath", "readlink", "date", "uname",
            "node", "python", "python3", "java", "go", "cargo", "gradle" ->
                readonly(base)

            "mkdir", "touch", "mv", "cp", "ln", "chmod", "chown", "sed", "tee", "patch", "install",
            "make", "nano", "vi", "vim", "tar", "zip", "unzip" ->
                modify(base)

            else -> CommandClassification(
                CommandCategory.UNKNOWN,
                ToolRiskLevel.HIGH,
                "Unrecognized command '$base'; approval is always required for it"
            )
        }
    }

    private fun classifyGit(sub: String, allArgs: String): CommandClassification = when (sub) {
        "reset" -> if (allArgs.contains("--hard")) destructive("git reset --hard") else modify("git reset")
        "clean" -> destructive("git clean")
        "push", "fetch", "pull", "clone", "remote", "ls-remote", "submodule" -> network("git $sub")
        "status", "log", "diff", "show", "branch" -> readonly("git $sub")
        "add", "commit", "checkout", "switch", "restore", "stash", "merge", "rebase", "tag", "init" -> modify("git $sub")
        else -> modify("git $sub")
    }

    private fun readonly(what: String) =
        CommandClassification(CommandCategory.READ_ONLY, ToolRiskLevel.LOW, "'$what' only reads project state")

    private fun modify(what: String) =
        CommandClassification(CommandCategory.MODIFY_PROJECT, ToolRiskLevel.MEDIUM, "'$what' may modify project files")

    private fun install(what: String) =
        CommandClassification(CommandCategory.INSTALL_PACKAGE, ToolRiskLevel.HIGH, "'$what' installs software")

    private fun network(what: String) =
        CommandClassification(CommandCategory.NETWORK, ToolRiskLevel.HIGH, "'$what' may access the network")

    private fun destructive(what: String) =
        CommandClassification(CommandCategory.DESTRUCTIVE, ToolRiskLevel.CRITICAL, "'$what' can destroy data")

    private fun packageRemove(what: String) =
        CommandClassification(CommandCategory.PACKAGE_REMOVE, ToolRiskLevel.CRITICAL, "'$what' removes installed software")

    private fun system(what: String) =
        CommandClassification(CommandCategory.SYSTEM, ToolRiskLevel.HIGH, "'$what' changes system state")

    private val PREFIXES = setOf("sudo", "doas", "env", "time", "nice", "nohup", "command", "exec")

    private fun hasMetacharacters(command: String): Boolean =
        META_CHARS.any { command.contains(it) }

    private val META_CHARS = listOf("&&", "||", ";", "|", "`", "$(", ">", "<", "&")

    /** Split on shell sequencing operators without attempting to execute anything. */
    private fun splitSegments(command: String): List<List<String>> =
        command.split(Regex("""(?:&&|\|\||;|\||\n)"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { tokenize(it) }
            .filter { it.isNotEmpty() }

    private fun tokenize(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in segment) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
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
}
