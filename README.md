# Signal Lab — recommendation playground

Published 2026-10-11 · Updated 2026-10-11

A SolidJS/Vite frontend and Java 21/Quarkus API demonstrate a model configuration regression in Corridora. The ranking is deterministic: changing popularity and quality weights changes real ordering among three fictional products. No AI subscription, prompt retention, database server or paid inference is required.

```text
SolidJS + Corridora RUM → Quarkus ranking API → optional AWS review leaf
                                  ↓              bounded fixture fallback
                      Cloud Storage model/config/heartbeat
                                  ↓
                     Pub/Sub refresh → authenticated consumer
                                  ↓
                 Java OpenTelemetry traces + logs + metrics → Corridora
Cloud Logging request sink → Pub/Sub adapter ────────────────────────┘
Cloud Monitoring request-count poll → Java metrics ──────────────────┘
```

The API serves the built frontend from the same Cloud Run origin. Contributors target their own Corridora organization. Maintained deployment uses operator-provisioned Demo keys; no shared credentials are distributed in this repository. Start with [Set up Corridora](docs/corridora-setup.md).

## Run locally

Prerequisites: Python 3.10+, Node 22.12+, npm, a Java 21 JDK, Maven 3.9.11+, and Git. Browser verification also needs Chromium (`cd web && npx playwright install chromium`). Builds need registry access. `./setup --help` needs only Python.

```sh
./setup
```

The wizard collects configuration, installs/builds locked dependencies, starts the app, runs the actual browser regression journey, and keeps the app running until Ctrl+C. The seeded collection lives at `http://127.0.0.1:4107`.

Repeatable commands:

| Command | Local mode | Cloud mode |
| --- | --- | --- |
| `./setup configure` | Prompt and save settings | Prompt for existing GCP project/account and resource choices too |
| `./setup deploy --plan` | Describe local build | Show account/project resources, limits and cost assumptions without login or changes |
| `./setup deploy` | Build frontend + Java; validate dependency lock | Actually provision resources and build/deploy the container with gcloud |
| `./setup run` | Start foreground Quarkus server | Check health and print deployed URL |
| `./setup verify` | Browser healthy → regression → seeded error → healthy control → recovery | Same journey against deployed service; write successful daily heartbeat |
| `./setup scenario --phase regression --duration 120` | Expiring authenticated model rollout | Same, stored in Cloud Storage across instances |
| `./setup scenario --phase recover --duration 120` | Healthy override | Healthy override; natural schedule resumes after expiry |
| `./setup destroy --plan` | Show cloud inventory if any | Preview owned resources to delete |
| `./setup destroy` | Delete recorded cloud resources only, if any | Teardown recorded owned resources; preserve shared project/bucket/other apps |

`--non-interactive` accepts environment variables matching `.env.example`, with no secret CLI arguments. `--config /protected/config.json` merges a JSON config; environment wins over saved values. The script does **not** execute `.env` files. Secrets are masked when prompted, saved separately in Git-ignored `.local/secrets.json` with mode `0600`, and mounted from one Secret Manager secret in cloud mode. Nonsecret settings and ownership inventory are also under `.local/`. Retain that directory for resumable deployment and safe teardown.

## Workflow and daily regression

- Run ranking, change the weight sliders, and compare the best match. `GET /api/recommendations?product_id=demo-product` returns the shared contract `items` and `model_revision`, plus inputs, review source and fallback state.
- Each day **15:00–15:10 UTC**, the effective `fixture-bad-v2` revision adds a bounded 1.5-second ranking pass to `demo-product` and `demo-invalid`. The latter additionally returns a seeded HTTP 500. `control-product` remains healthy.
- Every handler derives this window from UTC, regardless of missed Scheduler execution. Authenticated overrides support `healthy`, `regression`, `recover` and require an integer duration from 1 to 600 seconds. Expiry is checked on requests and consumed messages.
- `demo.release` describes the simulated model rollout. Actual `service.version` comes from the built Git commit or source digest; it never changes merely because an incident starts. Change/recovery events carry `demo.simulated=true`, stable `demo.scenario=ranking-model-change`, and a separate `demo.phase`.
- The Playwright journey in `web/tests/journey.spec.ts` visits the actual SolidJS UI, observes slowdown/error, checks a healthy control and recovers. It posts a successful heartbeat only after completing the journey.
- `.github/workflows/daily.yml` runs at 15:01 and 15:20 UTC. The second run is a watchdog: missing successful heartbeat opens a fresh bounded override and repeats the browser workflow. See [deployment](docs/deployment.md) for enabling it and schedule limitations.

The fixed seeded fixture affects no real user data. `/healthz`, `/api/trace-check`, `/api/demo/status` and all API responses are noncached. The status response includes effective phase, release, expiry, model revision and actual build version, and exposes no keys.

## Cross-cloud and refresh

Set `REVIEW_SUMMARY_URL=https://<reviews-host>/internal/review-summary` to call the AWS sample's **leaf** endpoint. It has a one-second timeout, no redirects, explicit W3C propagation, an optional separate `DOWNSTREAM_AUTH_TOKEN`, and a deterministic 4.5-star fallback. Ingest/control credentials never reach AWS. Other paths, query-string secrets, non-HTTPS remote URLs and URLs with user information are rejected. Empty configuration uses a local fixture; no other app is required.

Authenticated `POST /api/refresh?revision=fixture-v2` publishes model configuration with W3C message attributes. Cloud Pub/Sub pushes to `/internal/pubsub`; the consumer verifies its Google OIDC issuer/audience/email, extracts the PRODUCER context, links the push SERVER span and writes the model revision to Storage. Local mode writes the same fixture config directly. DLQ subscriptions cap retained messages at one day; the configured delivery attempt count is best effort, not an exact retry guarantee.

Paid AI adapters and real advertising tags are intentionally absent. Adding either requires a separate consent/budgeted integration; the deterministic incident continues to work independently.

## Validate without using Demo

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
cd web
npm ci --ignore-scripts
npm run build
cd ..
mkdir -p src/main/resources/META-INF/resources
cp -R web/dist/. src/main/resources/META-INF/resources/
mvn -B package
python3 scripts/check-java-lock.py
python3 tests/e2e.py
```

`tests/e2e.py` starts an isolated Java process, local OTLP capture server and AWS leaf stub. It checks control authentication, duration validation, expiry, model refresh, Pub/Sub consumer parentage, all three OTLP signal endpoints, backend key isolation, outbound dependency propagation, and the real browser regression journey. It uses only invented local keys and never contacts shared Demo ingestion.

To additionally prove actual Corridora browser CLIENT → Java SERVER parentage, obtain a reviewed Corridora RUM distribution and set `RUM_BUNDLE_PATH=/path/to/corridora-rum.js` for that test. The harness serves that real unmodified script locally. It compares captured trace IDs **and** parent span IDs; a generated header alone is insufficient. `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` optionally selects an existing browser. Without the bundle, the test explicitly reports browser/server correlation as unverified. Results go to ignored `.local/validation-summary.json`.

Java versions are pinned in `pom.xml`; `dependency-lock.txt` records the resolved 153-artifact runtime graph and `scripts/check-java-lock.py` rejects graph drift. npm uses `web/package-lock.json`. After intentional Java dependency updates, review and regenerate the graph from `target/quarkus-app/quarkus-app-dependencies.txt`. Container base images have verified version tags and pinned public-registry digests; review patch updates before refreshing them.

No live cloud resources or stored Demo traces are validated by local tests. This repository includes real deployment commands and adapters, but cloud IAM policies, billing, token delivery, Docker multi-stage build and hosted daily browser scheduling must be checked in the chosen project.
