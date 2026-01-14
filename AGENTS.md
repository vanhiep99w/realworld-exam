# AGENTS.md - Realworld Example Project (Java + React)

## Architecture
- **be/**: Spring Boot 4.0 backend (Java 21, Gradle, JPA, PostgreSQL, Lombok)
- **fe/**: React 19 frontend (TypeScript, Vite, TanStack Router, Vitest)
- **docs/**: Technical documentation for implemented features

## Commands
### Backend (run from `be/`)
- Build: `./gradlew build`
- Test all: `./gradlew test`
- Single test: `./gradlew test --tests "ClassName.methodName"`
- Run: `./gradlew bootRun`

### Frontend (run from `fe/`)
- Dev: `bun dev` or `npm run dev` (port 3000)
- Build: `bun run build`
- Test all: `bun test`
- Single test: `bun test <filename>`

### Docker
- Start LocalStack: `docker-compose up -d`
- Stop: `docker-compose down`

## Code Style
- **Java**: Package `com.seft.learn.*`, use Lombok, JUnit 5, records for DTOs
- **TypeScript**: Strict mode, path alias `@/*` → `./src/*`, ES2022 target
- **Imports**: Use absolute imports with `@/` prefix in frontend

## Implemented Examples
| Feature | Backend | Frontend | Docs |
|---------|---------|----------|------|
| S3 Presigned URL (PUT/POST/GET) | `controller/S3Controller.java` | `components/FileUpload.tsx` | `docs/S3_PRESIGNED_URL.md` |
| Large Data Export (10M+ rows) | `service/ExportService.java` | - | `docs/LARGE_DATA_EXPORT.md` |

## Documentation
When implementing new features, create/update documentation in `docs/` folder with:
- Overview and flow diagrams
- Code examples for both BE and FE
- Official documentation links
