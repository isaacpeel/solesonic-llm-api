# Contributing Guide

Thank you for your interest in contributing to the Solesonic LLM API! This document provides guidelines for setting up your development environment and contributing to the project.

## Development Environment Setup

### Prerequisites

- **Java 24** - Required for building and running the application
- **Maven 3.8+** - For build management and dependencies
- **Docker and Docker Compose** - For local database and service dependencies
- **Git** - For version control
- **IDE** - IntelliJ IDEA (recommended) or Eclipse with Spring Tools

### Getting Started

1. **Fork and Clone**
   ```bash
   # Fork the repository on GitHub, then clone your fork
   git clone https://github.com/your-username/solesonic-llm-api.git
   cd solesonic-llm-api
   
   # Add upstream remote
   git remote add upstream https://github.com/original-repo/solesonic-llm-api.git
   ```

2. **Set Up Local Environment**
   ```bash
   # Copy example environment file
   cp .env.example .env
   
   # Edit .env with your local configuration
   # See docs/configuration.md for details
   ```

3. **Start Dependencies**
   ```bash
   # Start PostgreSQL database with pgvector
   docker compose -f docker/docker-compose-db.yml up -d
   
   # Ensure Ollama is running (install separately)
   ollama serve
   ```

4. **Build and Test**
   ```bash
   # Build the project
   ./mvnw clean verify
   
   # Run tests
   ./mvnw test
   
   # Run the application locally
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

### IDE Setup

#### IntelliJ IDEA (Recommended)

1. **Import Project**
   - Open IntelliJ IDEA
   - File → Open → Select project root directory
   - Import as Maven project

2. **Configure Run Configuration**
   - Run → Edit Configurations
   - Add new Spring Boot configuration
   - Set main class: `com.solesonic.llmapi.SolesonicLlmApiApplication`
   - Set active profiles: `local`
   - Set environment variables from your `.env` file

3. **Code Style**
   - Import code style configuration (if provided)
   - Enable "Reformat code" and "Optimize imports" on save

#### Eclipse

1. **Import Project**
   - File → Import → Existing Maven Projects
   - Select project root directory

2. **Install Spring Tools**
   - Help → Eclipse Marketplace
   - Search for "Spring Tools 4" and install

## Coding Guidelines

### Java Conventions

- **Java 24 features**: Use modern Java features appropriately
- **Constructor injection**: Prefer constructor injection over field injection
- **Descriptive names**: Use clear, descriptive variable and method names
- **No unused imports**: Clean up imports regularly
- **Exception handling**: Handle exceptions appropriately with meaningful messages

### Code Style

```java
// Good: Constructor injection with descriptive names
@Service
public class ChatService {
    private final ChatRepository chatRepository;
    private final LlmService llmService;
    
    public ChatService(ChatRepository chatRepository, LlmService llmService) {
        this.chatRepository = chatRepository;
        this.llmService = llmService;
    }
    
    public ChatResponse processMessage(String userId, String message) {
        // Implementation with proper error handling
        try {
            return processMessageInternal(userId, message);
        } catch (Exception e) {
            log.error("Failed to process message for user {}: {}", userId, e.getMessage(), e);
            throw new ChatProcessingException("Unable to process message", e);
        }
    }
}
```

### What to Avoid

- **No brackets in if statements**: Always use brackets
  ```java
  // Bad
  if (condition) doSomething();
  
  // Good
  if (condition) {
      doSomething();
  }
  ```

- **TODO comments**: Don't leave TODO comments in committed code
- **Excessive comments**: Code should be self-documenting
- **AI-generated looking code**: Write natural, human-readable code

### Testing Guidelines

- **Unit tests**: Write unit tests for new functionality
- **Integration tests**: Add integration tests for API endpoints
- **No test comments**: Avoid "Arrange", "Act", "Assert" comments
- **Descriptive test names**: Use clear, descriptive test method names

```java
@Test
void shouldCreateNewChatWhenUserSendsFirstMessage() {
    // Test implementation without AAA comments
    String userId = "test-user-123";
    String message = "Hello, world!";
    
    ChatResponse response = chatService.createChat(userId, message);
    
    assertThat(response.getChatId()).isNotNull();
    assertThat(response.getMessages()).hasSize(2); // User + Assistant
}
```

## Database Changes

### Flyway Migrations

When making database schema changes:

1. **Create migration file**
   ```bash
   # Follow naming convention: V{version}__{description}.sql
   # Example: V2_5__add_chat_metadata_table.sql
   touch src/main/resources/db/migration/V2_5__add_chat_metadata_table.sql
   ```

2. **Write SQL migration**
   ```sql
   -- V2_5__add_chat_metadata_table.sql
   CREATE TABLE chat_metadata (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       chat_id UUID NOT NULL,
       metadata_key VARCHAR(255) NOT NULL,
       metadata_value TEXT,
       created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
       FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
   );
   
   CREATE INDEX idx_chat_metadata_chat_id ON chat_metadata(chat_id);
   CREATE INDEX idx_chat_metadata_key ON chat_metadata(metadata_key);
   ```

3. **Test migration**
   ```bash
   # Start with clean database
   docker compose -f docker/docker-compose-db.yml down -v
   docker compose -f docker/docker-compose-db.yml up -d
   
   # Run application to apply migrations
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

## Configuration Changes

### Environment Variables

When adding new configuration:

1. **Add to canonical list**: Update `docs/configuration.md`
2. **Add to application properties**: Include defaults in `application*.properties`
3. **Update examples**: Update `.env.example` and documentation samples

Example:
```bash
# In docs/configuration.md
| `NEW_FEATURE_ENABLED` | Enable new feature | `true` | No | Default: false |

# In application-local.properties
solesonic.feature.new-feature.enabled=${NEW_FEATURE_ENABLED:false}
```

## API Changes

### Adding New Endpoints

1. **RESTful design**: Follow REST principles
2. **Consistent paths**: Use `/izzybot` prefix for main API endpoints
3. **Standard responses**: Use consistent response formats
4. **Error handling**: Implement proper error responses
5. **Documentation**: Update `docs/api.md`

Example:
```java
@RestController
@RequestMapping("/izzybot/users")
public class UserController {
    
    @GetMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> getUserPreferences(@PathVariable String userId) {
        UserPreferences preferences = userService.getPreferences(userId);
        return ResponseEntity.ok(preferences);
    }
}
```

## Pull Request Process

### Before Submitting

1. **Update your fork**
   ```bash
   git fetch upstream
   git checkout main
   git merge upstream/main
   git push origin main
   ```

2. **Create feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make changes**
   - Follow coding guidelines
   - Add tests for new functionality
   - Update documentation if needed

4. **Test changes**
   ```bash
   # Run all tests
   ./mvnw clean verify
   
   # Test locally
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

5. **Commit changes**
   ```bash
   git add .
   git commit -m "Add feature: descriptive commit message"
   git push origin feature/your-feature-name
   ```

### PR Guidelines

#### PR Title and Description

- **Clear title**: Summarize the change in the title
- **Detailed description**: Explain what changed and why
- **Breaking changes**: Highlight any breaking changes
- **Testing**: Describe how you tested the changes

Example:
```markdown
## Add user preferences API endpoint

### Changes
- Added new `UserController` with preferences endpoint
- Created `UserPreferences` entity and repository
- Added migration for user_preferences table
- Updated API documentation

### Testing
- Added unit tests for controller and service
- Tested locally with Postman
- Verified database migration works correctly

### Breaking Changes
None
```

#### PR Checklist

- [ ] Code follows project coding guidelines
- [ ] Tests added for new functionality
- [ ] Documentation updated (if applicable)
- [ ] Database migrations tested (if applicable)
- [ ] No breaking changes (or clearly documented)
- [ ] All tests pass locally
- [ ] Branch is up to date with main

### Review Process

1. **Automated checks**: Ensure CI builds pass
2. **Code review**: Address reviewer feedback
3. **Documentation**: Update docs if needed
4. **Final approval**: Maintainer approval required

## Development Workflow

### Branch Strategy

- **main**: Production-ready code
- **feature/**: Feature development branches
- **hotfix/**: Critical bug fixes
- **release/**: Release preparation branches

### Commit Messages

Use conventional commit format:

```
type(scope): description

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

Examples:
```
feat(chat): add intent-based message routing

fix(auth): resolve JWT token validation issue

docs(api): update endpoint documentation for v2
```

## Release Process

### Version Management

- **Semantic versioning**: MAJOR.MINOR.PATCH
- **Release notes**: Document changes in each release
- **Migration guides**: Provide upgrade instructions for breaking changes

### Release Steps

1. **Prepare release branch**
2. **Update version numbers**
3. **Update documentation**
4. **Create release notes**
5. **Tag release**
6. **Deploy to staging**
7. **Final testing**
8. **Deploy to production**

## Getting Help

### Development Support

- **Documentation**: Check existing documentation first
- **Issues**: Search existing issues on GitHub
- **Discussions**: Use GitHub Discussions for questions
- **Code review**: Ask for feedback during PR review

### Useful Development Commands

```bash
# Build without running tests (faster development)
./mvnw clean compile -DskipTests

# Run specific test class
./mvnw test -Dtest=ChatServiceTest

# Run integration tests only
./mvnw verify -Pintegration-tests

# Generate test coverage report
./mvnw jacoco:report

# Check for dependency updates
./mvnw versions:display-dependency-updates
```

### Debugging Tips

1. **Use IDE debugger**: Set breakpoints and step through code
2. **Enable debug logging**: Set `LOGGING_LEVEL_COM_SOLESONIC=DEBUG`
3. **Check health endpoints**: Use `/actuator/health` for diagnostics
4. **Database inspection**: Connect to PostgreSQL directly for data verification

## Code Quality

### Static Analysis

Consider using:
- **SonarQube**: Code quality analysis
- **SpotBugs**: Bug detection
- **PMD**: Code analysis
- **Checkstyle**: Code style enforcement

### Performance Considerations

- **Database queries**: Optimize N+1 queries
- **Caching**: Implement appropriate caching strategies
- **Memory usage**: Monitor memory consumption
- **Response times**: Keep API responses fast

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md) - Initial setup
- **Configuration**: [docs/configuration.md](configuration.md) - Environment variables
- **API Documentation**: [docs/api.md](api.md) - API reference
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md) - Common issues
- **Security**: [docs/security.md](security.md) - Security considerations