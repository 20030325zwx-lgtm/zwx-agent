# Local Development Contract

## Non-Negotiable: Preserve Local Configuration

- **Never delete, rename, overwrite, reset, replace, or regenerate** `src/main/resources/application-local.yml`.
- This file contains the working local PostgreSQL connection and user-managed secrets for the local service.
- It is intentionally Git-ignored. Do not add it to Git, display its secret values, copy it into documentation, or include it in patches.
- Before changing any configuration, read this file only as needed and preserve all existing keys and values. Additive edits require an explicit user request.
- Never run destructive Git commands or cleanup commands that could remove ignored files. In particular, do not use `git clean`, `git reset --hard`, or broad deletion commands in this repository.
- If `application-local.yml` is missing or invalid, stop and tell the user. Do not recreate it with guessed values.

## Verified Local Runtime

- Backend: Spring Boot at `http://127.0.0.1:8123/api`; health check: `GET /health` returns `ok`.
- Frontend: Vite at `http://127.0.0.1:3000/`; it defaults to the local backend API.
- PostgreSQL + pgvector: Docker container `yu-ai-agent-postgres`, exposed on `127.0.0.1:5432`.
- MinIO: Docker container `yu-ai-agent-minio`, exposed on `127.0.0.1:9000` (API) and `127.0.0.1:9001` (console).

## Start and Restart

1. Keep the Docker PostgreSQL container running.
2. Build the backend with `mvn -DskipTests package` from the repository root. Do not use the repository's `mvnw`; it is not executable in this checkout. After any backend source change, rebuild the JAR before restarting; `compile` alone does not update `target/zwx-agent-0.0.1-SNAPSHOT.jar`.
3. Start the backend with:
   ```sh
   java -jar target/zwx-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
   ```
4. Start the frontend from `zwx-agent-frontend` with:
   ```sh
   npm run dev -- --host 127.0.0.1
   ```
5. Verify `curl -fsS http://127.0.0.1:8123/api/health` returns `ok` and the frontend returns HTTP 200.

Use the local service log monitor when starting long-running processes if it is available.

### macOS Persistent Start

When a shell-launched background process does not survive after the terminal command exits, use user-level `launchctl` jobs instead. Run these commands from any shell (replace an existing job with `launchctl remove` first if needed):

```sh
launchctl submit -l com.zwx.yu-ai-agent.backend -- /bin/zsh -lc 'cd /Users/zhuwenxuan/Desktop/agent/yu-ai-agent && exec /usr/bin/java -jar target/zwx-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=local > /tmp/yu-ai-agent-backend.log 2>&1'
launchctl submit -l com.zwx.yu-ai-agent.frontend -- /bin/zsh -lc 'cd /Users/zhuwenxuan/Desktop/agent/yu-ai-agent/zwx-agent-frontend && exec /usr/local/bin/node node_modules/vite/bin/vite.js --host 127.0.0.1 > /tmp/yu-ai-agent-frontend.log 2>&1'
```

Restart or stop them with:

```sh
launchctl kickstart -k "gui/$(id -u)/com.zwx.yu-ai-agent.backend"
launchctl kickstart -k "gui/$(id -u)/com.zwx.yu-ai-agent.frontend"
launchctl kill SIGTERM "gui/$(id -u)/com.zwx.yu-ai-agent.backend"
launchctl kill SIGTERM "gui/$(id -u)/com.zwx.yu-ai-agent.frontend"
```
