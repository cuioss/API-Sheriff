# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

API Sheriff is a security-focused API Gateway with a lightweight approach, currently in pre-1.0 development phase. The project follows CUI (CUIoss) standards and is built using Maven with Java 21+.

## Build Commands

### Core Build Operations
- **Build project**: `./mvnw clean install`
- **Build without tests**: `./mvnw clean install -DskipTests`
- **Run tests only**: `./mvnw test`
- **Run single test**: `./mvnw test -Dtest=ClassName#methodName`

### Quality and Pre-Commit Process
**CRITICAL**: Always run these commands before committing:

1. **Quality verification** (fix all errors/warnings): 
   ```bash
   ./mvnw -Ppre-commit clean verify -DskipTests
   ```
2. **Final verification** (must pass completely):
   ```bash
   ./mvnw clean install
   ```

### CI/CD Commands
- **Sonar analysis**: `./mvnw verify -Psonar sonar:sonar`
- **Deploy snapshot**: `./mvnw -Prelease-snapshot,javadoc deploy`
- **Coverage report**: `./mvnw clean verify -Pcoverage`

## Project Structure

```
api-sheriff/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── de/cuioss/sheriff/api/  # Main source code
│   └── test/
│       └── java/
│           └── de/cuioss/sheriff/api/  # Test code
├── doc/
│   └── ai-rules.md                     # AI development guidelines
├── pom.xml                              # Maven configuration
└── lombok.config                        # Lombok configuration
```

## Critical Development Rules

### Pre-1.0 Project Rules (HIGHEST PRIORITY)
- **NEVER deprecate code** - Remove it directly
- **NEVER add @Deprecated annotations** - Delete unnecessary code immediately  
- **NEVER enforce backward compatibility** - Make breaking changes freely
- **Clean APIs aggressively** - Remove unused methods, classes, and patterns
- **Focus on final API design** - Design for post-1.0 stability

### CUI Standards Compliance

#### Logging
- Use `de.cuioss.tools.logging.CuiLogger` (private static final LOGGER)
- Exception parameter always comes first in logging methods
- Use '%s' for string substitutions (not '{}' or '%d')
- Document all log messages in doc/LogMessages.adoc

#### Testing
- Use JUnit 5 exclusively
- Follow AAA pattern (Arrange-Act-Assert)
- Minimum 80% code coverage required
- Use cui-test-generator for test data generation
- **Forbidden**: Mockito, PowerMock, Hamcrest

#### Java Standards
- Java 21+ features encouraged (records, switch expressions, text blocks)
- Use Lombok annotations (@Builder, @Value, @NonNull, @UtilityClass)
- Prefer immutable objects and final fields
- Return empty collections instead of null
- Use Optional for nullable return values

#### Documentation
- Every public API must have complete Javadoc
- Use AsciiDoc format (.adoc) for documentation
- Include @since tags with version information
- Document thread-safety considerations

## Dependency Management

Current minimal dependencies:
- **lombok**: For boilerplate reduction
- **junit-jupiter-api**: For testing
- **Parent POM**: de.cuioss:cui-java-parent:1.1.4

**CRITICAL**: Never add dependencies without explicit user approval

## Integration with IntelliJ IDEA

The project is configured for IntelliJ IDEA with:
- MCP server integration for enhanced IDE features
- Maven wrapper for consistent builds
- EditorConfig for code formatting

Use IntelliJ MCP tools when available:
- `mcp__jetbrains__get_file_problems` for code analysis
- `mcp__jetbrains__execute_terminal_command` for running commands
- `mcp__jetbrains__search_in_files_by_text` for efficient code search

## Security Considerations

As a security-focused API Gateway:
- All changes must consider security implications
- Never expose sensitive data in logs
- Follow OWASP security guidelines
- Validate all inputs and outputs
- Use secure defaults for all configurations

## Common Tasks

### Adding New Features
1. Research existing patterns in the codebase
2. Follow CUI standards for implementation
3. Write comprehensive tests (80%+ coverage)
4. Document public APIs with Javadoc
5. Run pre-commit checks before committing

### Fixing Issues
1. Identify the issue using IDE diagnostics
2. Apply fix following existing code patterns
3. Add/update tests to prevent regression
4. Verify with `./mvnw clean install`

### Refactoring
1. Ensure tests pass before starting
2. Make incremental changes
3. Run tests after each change
4. Use IDE refactoring tools when available
5. Clean up aggressively (pre-1.0 rule)