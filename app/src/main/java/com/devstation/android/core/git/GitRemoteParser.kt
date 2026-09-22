package com.devstation.android.core.git

/**
 * Phase 10 §16: Git remote parser.
 * Parses output from `git remote -v`.
 */
object GitRemoteParser {

    fun parse(output: String): List<GitRemote> {
        val lines = output.lines().filter { it.isNotBlank() }
        val remotesMap = mutableMapOf<String, Pair<String, String>>()

        for (line in lines) {
            val parts = line.split(Regex("""\s+"""))
            if (parts.size < 3) continue

            val name = parts[0]
            val url = parts[1]
            val type = parts[2].trim('(', ')')

            val current = remotesMap[name] ?: Pair("", "")
            remotesMap[name] = when (type.lowercase()) {
                "fetch" -> Pair(url, current.second.ifEmpty { url })
                "push" -> Pair(current.first.ifEmpty { url }, url)
                else -> Pair(url, url)
            }
        }

        return remotesMap.map { (name, urls) ->
            GitRemote(
                name = name,
                fetchUrl = urls.first,
                pushUrl = urls.second
            )
        }
    }
}
