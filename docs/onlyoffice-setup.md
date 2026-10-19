# Local ONLYOFFICE trial

The trial is a separate Compose project named `office-trial`. It does not reuse the original application/database containers or their data. Open the trial application at **http://localhost:8090**. ONLYOFFICE Docs runs at **http://localhost:8082**. Use `localhost` consistently because editor configuration contains that browser origin.

| Service | Browser/host address | Docker address |
| --- | --- | --- |
| Trial application and plugin | `http://localhost:8090` | `http://office-app:8080` |
| ONLYOFFICE Community | `http://localhost:8082` | `http://office-docs` |
| Trial PostgreSQL | No published port | `office-db:5432` |

The application and document server publish loopback ports only. The existing app on 8081 and database on 5432 are managed separately by the original `docker-compose.yml`.

## Host backend startup (generation sandbox supported)

Use the host backend when generating documents. Java runs directly on Linux so its mandatory Bubblewrap sandbox can create namespaces; ONLYOFFICE and PostgreSQL keep the existing `office-trial` Docker project and named volumes. No host security setting or Docker security profile is relaxed.

Requirements: Java 21, Node 24, Bubblewrap, `prlimit`, Python 3 with Linux pidfd support, installed project Node dependencies, Docker Compose, and a freshly packaged application. From the project directory:

```sh
scripts/office-init
npm ci
mvn package
scripts/office-native up
```

The launcher discovers Java 21 and Node 24, including the JetBrains Node runtime. Set `OFFICE_JAVA_BINARY` or `OFFICE_NODE_BINARY` to an absolute executable path to select a different installed runtime. It loads literal single-line `.env` values privately, without shell execution or variable interpolation; inherited provider settings take precedence. Trial JWT/database secrets come from `.office.local.env`. Values are never printed or passed as command-line arguments.

The first `up` checks the host sandbox before stopping the old Docker backend. It refuses the transition while generation jobs are active, copies existing trial files into `.local/office/files`, retains the original Docker volume, makes a full private PostgreSQL backup, and updates legacy document/image paths in one transaction. Interrupted migrations resume from `.local/office/migration.json`; do not delete that file or move the workspace after migration. The backup and original paths are retained under `.local/office/backup-*`. Do not start the old Docker backend after the migration because its storage paths are no longer valid there.

The application listens on `127.0.0.1:8090`, PostgreSQL is published only at `127.0.0.1:55434`, and Docs remains at `127.0.0.1:8082`. A second application listener binds only the trial Docker bridge gateway on port 8091; it permits the signed document download and ONLYOFFICE callback routes. Browser and general application endpoints remain on loopback. Readiness requires a fresh successful sandbox DOCX generation check plus the database-backed budget endpoint; startup makes no provider calls. Refresh existing editor pages after switching so they receive the new signed callback URLs.

```sh
scripts/office-native status
scripts/office-native stop
scripts/office-native up
```

`stop` stops only the recorded native Java process, checking its Linux process identity before sending a signal. Docs and PostgreSQL remain running. `up` restarts the backend after checking for active jobs. Backend logs are private at `.local/office/backend.log`; state directories are mode `0700`, files mode `0600`, and `.local/` is excluded from Git and Docker builds. For dependency status use `scripts/office-compose ps`. Do not run the original `office-up` or Compose commands that recreate/start `office-app` after switching to native mode. After source changes, run `mvn package` and `scripts/office-native up` again.

Offline launcher checks: `python3 scripts/office-native-test.py`.

## Container-only start and rebuild

Requirements: Docker Engine, Docker Compose supporting optional `env_file` entries (2.24+), Java 21, Maven, and Python 3. The image is large; allow several GB of disk space and several minutes for its first download/start.

From the project directory:

```sh
mvn package
scripts/office-up
```

`office-up` creates persistent trial secrets if absent, builds the existing Java/Node runtime and a trial application image from the packaged JAR, and waits for all three services to be healthy. Packaging happens explicitly before the Docker build; changing source without rerunning Maven leaves the old JAR in use.

The image is pinned to ONLYOFFICE Community **10.0.0**, including its pulled manifest digest in `compose.office.yml`. The app runtime uses the existing Dockerfile's `runtime` stage with its installed Node modules and Unicode fonts. `scripts/office-Dockerfile` adds the freshly packaged application JAR and runs as `appuser`.

To start only the document server and private database while developing the backend:

```sh
scripts/office-init
scripts/office-compose up -d --wait --wait-timeout 360 office-db office-docs
```

After another `mvn package`, rebuild and update the app with:

```sh
scripts/office-build
scripts/office-compose up -d --wait --wait-timeout 360
```

## Configuration and secrets

`scripts/office-init` creates `.office.local.env` with separate random 256-bit JWT/database secrets and mode `0600`. The file is ignored by Git and reused across restarts. Both the application and ONLYOFFICE receive the same JWT secret; JWT remains enabled for browser, inbound, and outbound requests. Do not delete or regenerate this file while retaining the database volume: the existing database password will no longer match.

The trial application loads the existing `.env` privately for provider configuration. No provider key is copied into the compose file, image, plugin, or browser. Explicit service settings override its database/storage configuration with the isolated trial values. Provide `ANTHROPIC_API_KEY` in `.env` before starting if not already configured. AI calls use the configured provider and can incur normal usage charges; service startup and ordinary document editing do not make provider calls.

The application settings are:

```text
OFFICE_ENABLED=true
OFFICE_PUBLIC_URL=http://localhost:8082
OFFICE_INTERNAL_URL=http://office-docs
OFFICE_APP_PUBLIC_URL=http://localhost:8090
OFFICE_APP_INTERNAL_URL=http://office-app:8080
```

`ALLOW_PRIVATE_IP_ADDRESS=true` lets ONLYOFFICE download signed document URLs from the app over the private Docker network. Containers retain Docker's normal security profile; no privileged mode or seccomp/AppArmor bypass is used. This is a local development configuration, not a public deployment recipe.

Avoid `docker compose config` without `-q` and broad environment/config dumps: resolved output contains secrets. The wrapper selects the trial compose file and secret file explicitly, preventing accidental use of the original stack.

## Health, logs, and stop

```sh
scripts/office-compose ps
scripts/office-compose config -q
curl -fsS http://localhost:8082/healthcheck
curl -fsS http://localhost:8090/api/budget
scripts/office-compose logs --tail=100 office-app
scripts/office-compose stop
scripts/office-compose start --wait
```

The document server health response is `true`; the budget endpoint verifies that the app can read its trial database. Container health checks wait for both dependencies before starting the backend. Logs may contain document metadata; review before sharing them.

`scripts/office-compose down` removes only trial containers/network and retains named volumes. Documents and database state survive. Do not add `--volumes` unless you deliberately want to erase the trial data.

## Scope and known limitation

This setup supports the DOCX editor/plugin trial. Existing documents can be edited through ONLYOFFICE independently of the generated-code sandbox. The current Java/Node generation path uses Bubblewrap, which is blocked by the host's default Docker security configuration. Generation paths that require that sandbox can fail in this container; the trial deliberately preserves container isolation rather than disabling seccomp/AppArmor. This does not establish support for every file format or generated-code workflow.

## Official references

- [ONLYOFFICE Community Docker installation and environment settings](https://helpcenter.onlyoffice.com/docs/installation/docs-community-install-docker.aspx)
- [JWT configuration](https://helpcenter.onlyoffice.com/docs/installation/docs-configure-jwt.aspx)
- [ONLYOFFICE DocumentServer 10.0.0 release](https://github.com/ONLYOFFICE/DocumentServer/releases/tag/v10.0.0)
- [Official Docker image implementation](https://github.com/ONLYOFFICE/Docker-DocumentServer)

## Using the trial

1. Open http://localhost:8090 (use `localhost`, not `127.0.0.1`).
2. Click **DOCX aç** and choose an existing `.docx` file, up to 50 MiB. The file appears in history and opens in the embedded editor. A generated DONE DOCX also has **Belgeyi düzenle**.
3. Use the editor toolbar for ordinary document edits.
4. Open **Eklentiler → Belge Asistanı**, select a paragraph, click **Seçimi al**, enter an instruction, and request a proposal. Review it, then click **Uygula**. This provider call uses the existing API configuration and budget ledger. Selection edits replace plain text and may alter mixed formatting within that selection.
5. Click the outer **Yaver’e kaydet** button. It waits for acknowledgment of its own save request, then shows the persisted version and download link. The editor's native save/synchronization indicator alone is not application persistence confirmation.
6. **Önizlemeye dön** returns to the saved job. Opening the original history entry follows the latest saved Office version. Earlier file bytes remain intact until the application's existing expiry cleanup.

Saved files use the application's existing temporary storage policy (72 hours by default); this is not permanent archival storage. The trial has its own history and budget ledger, separate from 8081. To edit an existing 8081 document, download its DOCX and open it in the trial. The original deployment has not been replaced.

The plugin SDK (`plugins.js`) is vendored at `src/main/resources/static/office-plugin/plugins.js` and served from the app itself — no `onlyoffice.github.io` CDN access is required, so the plugin works in air-gapped deployments too. Document content stays on the local services unless you explicitly request an AI edit.

## Verification

Run `mvn test`, `node src/test/js/frontend-audit.cjs`, `node src/test/js/office-editor.cjs`, and `python3 scripts/office-native-test.py` for automated checks. Java integration tests require the database and sandbox environment described in the test configuration and [node-sandbox.md](node-sandbox.md).

With the trial services running, `python3 scripts/office-smoke.py` checks document import, conversion, signed save callbacks, version persistence, duplicate suppression, reopening, browser resource policies, and unchanged API budget. It creates demonstration documents in the trial database and writes its report to `target/verification/office-smoke.json`.

The protocol smoke test replays a save callback; it does not type into the browser. Verify keyboard editing, plugin layout, selection, proposal review, applying changes, and saving interactively before delivery. Automated checks use mocked AI responses; requesting an AI edit in the browser uses the configured provider and budget.
