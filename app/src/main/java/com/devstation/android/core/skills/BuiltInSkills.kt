package com.devstation.android.core.skills

/**
 * Phase 8 §21: Safe built-in skills.
 *
 * Each built-in skill has conservative default permissions. None of them automatically
 * perform unrestricted shell commands. They define instructions that guide the AI and
 * require specific tools through the normal security pipeline.
 */
object BuiltInSkills {

    val all: List<SkillDefinition> = listOf(
        codeReview(),
        explainCode(),
        fixCompileError(),
        refactorCode(),
        generateTests(),
        projectSearch(),
        documentationWriter(),
        readmeGenerator()
    )

    private fun codeReview() = SkillDefinition(
        id = "builtin_code_review",
        name = "Code Review",
        description = "Review code for quality, style, and potential issues",
        author = "DevStation",
        instructions = """Review the provided code or the currently open file for:
1. Code quality and readability
2. Naming conventions
3. Potential bugs or edge cases
4. Performance concerns
5. Security issues
6. Style consistency

Provide specific, actionable feedback. Reference line numbers when possible.
Do not modify files — only report findings.""",
        requiredTools = listOf("read_file", "search_project", "list_directory"),
        requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ)
        ),
        source = SkillSource.BUILTIN
    )

    private fun explainCode() = SkillDefinition(
        id = "builtin_explain_code",
        name = "Explain Code",
        description = "Explain what a piece of code does, line by line if needed",
        author = "DevStation",
        instructions = """Explain the provided code or the currently open file clearly:
1. Describe the overall purpose
2. Explain key functions and their roles
3. Walk through important logic flows
4. Note any patterns or idioms used
5. Clarify complex sections

Use simple language. Assume the reader is a developer but may not know this specific code.""",
        requiredTools = listOf("read_file", "search_project"),
        requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ)
        ),
        source = SkillSource.BUILTIN
    )

    private fun fixCompileError() = SkillDefinition(
        id = "builtin_fix_compile_error",
        name = "Fix Compile Error",
        description = "Diagnose and fix compilation/type errors",
        author = "DevStation",
        instructions = """When given a compile error:
1. Read the error message carefully
2. Identify the file and line causing the error
3. Read the surrounding code for context
4. Determine the root cause
5. Apply the minimal fix
6. Verify the fix makes sense in context

If the fix is uncertain, explain the options and ask for guidance.
Never apply destructive changes to fix a compile error.""",
        requiredTools = listOf("read_file", "write_file", "apply_patch", "run_terminal_command", "search_project"),
        requestedCapabilities = listOf(
            SkillCapability.FILESYSTEM_READ,
            SkillCapability.FILESYSTEM_WRITE,
            SkillCapability.TERMINAL_READ
        ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(
                SkillCapability.FILESYSTEM_READ,
                SkillCapability.FILESYSTEM_WRITE,
                SkillCapability.TERMINAL_READ
            ),
            requiresTerminal = true
        ),
        source = SkillSource.BUILTIN
    )

    private fun refactorCode() = SkillDefinition(
        id = "builtin_refactor_code",
        name = "Refactor Code",
        description = "Refactor code for better structure while preserving behavior",
        author = "DevStation",
        instructions = """Refactor the provided code while preserving its behavior:
1. Understand the current behavior thoroughly
2. Identify the refactoring opportunity
3. Plan the changes before executing
4. Make incremental, verifiable changes
5. Preserve all existing functionality
6. Explain what changed and why

Never change behavior. Only improve structure, readability, or performance.""",
        requiredTools = listOf("read_file", "write_file", "apply_patch", "search_project", "run_terminal_command"),
        requestedCapabilities = listOf(
            SkillCapability.FILESYSTEM_READ,
            SkillCapability.FILESYSTEM_WRITE,
            SkillCapability.TERMINAL_READ
        ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(
                SkillCapability.FILESYSTEM_READ,
                SkillCapability.FILESYSTEM_WRITE,
                SkillCapability.TERMINAL_READ
            ),
            requiresTerminal = true
        ),
        source = SkillSource.BUILTIN
    )

    private fun generateTests() = SkillDefinition(
        id = "builtin_generate_tests",
        name = "Generate Tests",
        description = "Generate unit tests for a given file or function",
        author = "DevStation",
        instructions = """Generate tests for the provided code:
1. Read the source file
2. Understand the public API and behavior
3. Identify edge cases, boundary conditions, and error paths
4. Generate clear, focused test cases
5. Follow the project's existing test conventions
6. Create the test file in the appropriate location

Use descriptive test names. Each test should test one thing.""",
        requiredTools = listOf("read_file", "write_file", "create_file", "search_project", "list_directory"),
        requestedCapabilities = listOf(
            SkillCapability.FILESYSTEM_READ,
            SkillCapability.FILESYSTEM_WRITE
        ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(
                SkillCapability.FILESYSTEM_READ,
                SkillCapability.FILESYSTEM_WRITE
            )
        ),
        source = SkillSource.BUILTIN
    )

    private fun projectSearch() = SkillDefinition(
        id = "builtin_project_search",
        name = "Project Search",
        description = "Search the project for patterns, references, or definitions",
        author = "DevStation",
        instructions = """Search the project for the requested pattern:
1. Use the search_project tool with the given query
2. Read surrounding context for matches
3. Summarize findings clearly
4. Group results by file
5. Note patterns and frequency

Do not modify any files.""",
        requiredTools = listOf("search_project", "read_file", "list_directory"),
        requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ)
        ),
        source = SkillSource.BUILTIN
    )

    private fun documentationWriter() = SkillDefinition(
        id = "builtin_doc_writer",
        name = "Documentation Writer",
        description = "Generate or improve documentation for code",
        author = "DevStation",
        instructions = """Generate clear documentation for the provided code:
1. Read the source file
2. Understand the purpose, parameters, and return values
3. Write clear, concise documentation
4. Include usage examples where helpful
5. Follow the project's documentation conventions
6. Write the documentation to the appropriate location

Documentation should be accurate, complete, and readable.""",
        requiredTools = listOf("read_file", "write_file", "create_file", "search_project"),
        requestedCapabilities = listOf(
            SkillCapability.FILESYSTEM_READ,
            SkillCapability.FILESYSTEM_WRITE
        ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(
                SkillCapability.FILESYSTEM_READ,
                SkillCapability.FILESYSTEM_WRITE
            )
        ),
        source = SkillSource.BUILTIN
    )

    private fun readmeGenerator() = SkillDefinition(
        id = "builtin_readme_generator",
        name = "README Generator",
        description = "Generate or update a project README file",
        author = "DevStation",
        instructions = """Generate a comprehensive README for the project:
1. Read the project structure
2. Examine key source files, package.json, build configs
3. Understand the project's purpose
4. Generate a README with: title, description, features, installation, usage, structure
5. Write to README.md in the project root

Keep it accurate — only claim features that actually exist in the code.""",
        requiredTools = listOf("read_file", "write_file", "list_directory", "search_project"),
        requestedCapabilities = listOf(
            SkillCapability.FILESYSTEM_READ,
            SkillCapability.FILESYSTEM_WRITE
        ),
        securityProfile = SkillSecurityProfile(
            requestedCapabilities = listOf(
                SkillCapability.FILESYSTEM_READ,
                SkillCapability.FILESYSTEM_WRITE
            )
        ),
        source = SkillSource.BUILTIN
    )
}
