# Set up Corridora for Signal Lab

Published 2026-10-11 · Updated 2026-10-11

## Destination and credentials

Use `./setup configure`. Select `CORRIDORA_TARGET=own` for a contributor organization. For the maintained sample, an authorized operator selects `demo`, provides the Demo backend ingest key and separate browser RUM key, and uses environment `demo`. Demo's canonical UUID is `00000000-0000-4000-8000-000000000201`; **the supplied key routes ingestion**, so the UUID is never an authentication mechanism. Do not create keys through the public demo persona.

In your Corridora organization, create a project grouping these services:

| Service identity | Source |
| --- | --- |
| `sample-recommendations-web` | Corridora RUM loaded before SolidJS |
| `sample-recommendations-api` | Java SDK, ranking, dependency calls, refresh consumer and provider bridge |
| Namespace `corridora-samples.recommendations` | Shared stable grouping |

This compact deployment uses one Java service for API, consumer and adapter. `messaging.system=gcp_pubsub`, span kind and `event.name` distinguish roles. It does not invent separate worker identities for code that runs in the API process.

The backend **ingest key** is only for Java OTLP exporters. The **RUM key** is intentionally browser visible and must be scoped to browser collection and the deployment's allowed origins. A **management/query credential** has different powers and is never required by the application. No management token is included in a frontend build or cloud secret bundle.

## Prompt reference

The wizard's defaults are explicit and saved, making reruns reproducible:

| Variable | Meaning/default |
| --- | --- |
| `SAMPLE_MODE` | `local`; choose `cloud` for GCP |
| `CORRIDORA_TARGET` | `own`; `demo` requires operator-issued keys |
| `CORRIDORA_OTLP_ENDPOINT` | `https://ingest.corridora.com`, base URL without `/v1/*` |
| `CORRIDORA_RUM_SCRIPT_URL` | `https://rum.corridora.com/corridora-rum.js` |
| `CORRIDORA_INGEST_KEY` / `CORRIDORA_RUM_KEY` | Required, masked, separately scoped |
| `OTEL_SERVICE_NAME` / `OTEL_SERVICE_NAMESPACE` | API name and namespace above |
| `DEPLOYMENT_ENVIRONMENT` | `development`; forced to `demo` for Demo target |
| `PORT` | `4107`; Cloud Run injects its own port |
| `DEMO_CONTROL_TOKEN` | Masked, strong generated default; protects overrides, refresh and heartbeat |
| `DEMO_DAILY_START_UTC` / `DEMO_WINDOW_SECONDS` | `15:00` / `600`; bounded daily incident |
| `ENABLE_PROVIDER_FORWARDING` | `false`; enables Logging sink and Monitoring polling when true |
| `ENABLE_SYNTHETICS` | `false`; records operator intent, also enable the matching GitHub repository variable |
| `MODEL_REVISION` | `fixture-v1`; fixture model identifier |
| `REVIEW_SUMMARY_URL` | Empty local fixture; optional HTTPS AWS leaf endpoint |
| `DOWNSTREAM_AUTH_TOKEN` | Optional dedicated cross-cloud bearer token; never reuse ingest/control credentials |
| `GCP_ACCOUNT` | Optional explicit logged-in gcloud account; blank selects active identity |
| `GCP_PROJECT` / `GCP_REGION` | Existing billed project / `us-central1` |
| `GCP_PREFIX` | `sample-recs`; 3–20 lowercase letters, digits or hyphens |
| `GCP_SHARED_BUCKET` / `GCP_BUCKET` | `false` and automatic unique bucket; choose true and an explicit existing bucket to share with recipes |
| `CORRIDORA_QUERY_BASE_URL` / `CORRIDORA_QUERY_KEY` | Optional Corridora application base URL and organization **View API key**; used only by the setup verifier, never deployed to the app |

`PUBLIC_URL`, push identity, topic, bucket and actual service version are discovered or computed on deployment. Configure reruns update settings; `deploy --plan` shows a reviewable plan; applying deployment changes creates a new built revision. Python's wizard and the application's configuration must agree, so rerun deploy after changing cloud settings.

## Instrumentation and origins

`src/main/java/com/corridora/samples/Telemetry.java` creates the actual OpenTelemetry Java SDK and official OTLP HTTP exporters. It exports protobuf to `/v1/traces`, `/v1/logs` and `/v1/metrics`, adding `X-CORRIDORA-KEY` only to backend exporter requests. It uses resource attributes for service name/namespace, actual build version, deployment environment, GCP project, region, platform and Cloud Run revision.

Each API handler in `Api.java` extracts incoming W3C context before its SERVER span. `Ranking.java` creates ranking phases and an outbound review CLIENT span, forwarding context only to the explicitly configured AWS leaf. `Gcp.java` and `Api.java` propagate PRODUCER → CONSUMER context through Pub/Sub attributes, with a link to the push HTTP span. Provider API operations have explicit CLIENT spans; no claim is made that Google services export matching server spans.

Explicit logs include ranking completion, bounded dependency fallback, model rollout/recovery and provider request metadata. Metrics include ranking requests, forwarded request logs, daily browser heartbeat and native Cloud Run request counts. Exporter HTTP requests are not recursively instrumented. Handler completion ends spans and concurrently force-flushes traces/logs/metrics with a shared **1.4-second** wait budget; individual OTLP requests use a 900-ms timeout. Export failure is visible in process diagnostics and `/healthz.last_export_healthy`; local disabled credentials are reported separately.

`web/index.html` loads `/rum-config.js` before the SolidJS module. That endpoint exposes **only the RUM key**, app identity and collection settings, then inserts the configured external RUM script synchronously with `ckid`. The distribution owns the browser OTel provider. `web/src/index.tsx` uses `@opentelemetry/api` for the `ui.rank-products` action span and explicitly scopes the fetch initiation without leaking context across `await`.

RUM attributes include `data-endpoint`, `data-service`, `data-service-namespace`, `data-env`, `data-version`, `data-trace-sample-rate=1`, `data-long-tasks=true` and `data-trace-origins=location.origin`. Setup derives CSP from the exact RUM/ingest origins. Frontend and API share one origin, so no permissive cross-origin API policy is needed. The server-to-server AWS call is independent of browser CORS. Do not add vendor tracker origins to trace propagation.

## Provider data and cloud configuration

See [deployment](deployment.md) for the actual gcloud provisioning flow. `./setup deploy` mounts one Secret Manager JSON secret at `/secrets/config.json`; `Settings.java` loads it separately from nonsecret environment. It creates a refresh topic, authenticated push identity and DLQ; the push endpoint verifies the Google token's signature, issuer, audience and expected service-account email.

With `ENABLE_PROVIDER_FORWARDING=true`, a Cloud Logging sink filters only this Cloud Run service's request logs and excludes `/internal/` adapter/scheduler endpoints to prevent feedback. Pub/Sub pushes request entries to `/internal/provider-logs`, which converts whitelisted method/status/latency metadata into OTel log records and increments an adapter metric. It acknowledges only after an actual successful exporter receipt for that native log; bounded timeout/failure returns HTTP 503 for Pub/Sub retry/DLQ rather than silently dropping the message. It deliberately drops URL/query, header and payload fields. Native platform logs are independent of application SDK logs; this does not backfill Google Cloud Trace spans.

The scheduler polls a maximum of three Cloud Run request-count series over the prior 15 minutes, using the runtime identity's `roles/monitoring.viewer`. The latest available interval values become `gcp.cloud_run.request_count` gauges, labeled by region, service, revision and HTTP response code/class. Only the first three series are polled: this is truncated native-series evidence, not an aggregate total across the entire service. This is a small adapter, not a full Monitoring replication pipeline. Logging delivery, OIDC token validation, metric availability/latency and project-level read permissions require hosted validation. Keep default native log retention modest and recheck provider prices before enabling the entire fleet.

## Verify in Corridora

1. Run `./setup verify`, then open a fresh browser session in the selected Corridora project/environment. The script prints a recovered trace ID; follow `ui.rank-products` and a RUM fetch CLIENT span into the matching API SERVER span. Confirm **equal trace ID and SERVER parent ID equal to CLIENT span ID**.
2. Expand `ranking.compute`, the simulated extra pass and optional AWS leaf CLIENT span. Both sides of a cross-cloud continuation must be exported by their respective services to appear together.
3. Trigger an authenticated model refresh. Confirm PRODUCER/CONSUMER relationship and push SERVER link, application log trace/span IDs, a request metric, and `gcp.request`/native metric when forwarding is enabled.
4. Inspect the simulated change event with `demo.simulated=true`, scenario, release and actual service version. The browser should show ≥1.5 seconds extra ranking time and a seeded error for `demo-invalid`; `control-product` remains functional. Verify recovery and daily successful heartbeat.

An ingest HTTP 200 establishes acceptance only. The local `tests/e2e.py` test with `RUM_BUNDLE_PATH` proves emitted browser/server correlation to a capture sink, **not stored Corridora query results**. For automatic stored verification, set optional `CORRIDORA_QUERY_BASE_URL` to your Corridora application origin and `CORRIDORA_QUERY_KEY` to an organization **View API key**. Setup bounded-polls `GET /web/api/v1/traces/{trace_id}` with `X-Corridora-Api-Key`, then asserts stored web CLIENT and API SERVER trace/parent IDs. The key routes the organization; no tenant UUID is passed. It stays in the local protected secrets file and is never mounted into Cloud Run or passed to frontend/build subprocesses. Without it, setup explicitly leaves the stored UI checks pending.

## Rerun, rotate, recover and teardown

- Rerun `configure` and `deploy` to change a key, endpoint, model default or origin. Cloud secret versions are rotated and only the latest remains active; an application revision restart loads the updated bundle. Update daily-runner secrets too. Restrict the RUM key to the new allowed origin before enabling traffic.
- If no browser traces appear, inspect the script request, `ckid`, CSP and allowed origins. No backend key should be visible in browser sources/network traffic. If only backend spans appear, examine RUM bootstrap order and request `traceparent`.
- If exports fail, inspect `/healthz`, the OTLP base URL, `X-CORRIDORA-KEY` scope, timeout and process logs. Disable optional native forwarding while isolating a feedback issue.
- If Pub/Sub retries, inspect the DLQ subscription and token audience/email; do not replay poison records indefinitely. Fix the fixture/config, use a new bounded refresh, then acknowledge obsolete DLQ records using provider tooling.
- `./setup scenario --phase recover --duration 120` provides a bounded healthy override. Expiry returns to the normal UTC-derived schedule; it does not permanently disable daily faults.
- `./setup destroy --plan`, then `./setup destroy`, removes only inventory-owned resources. Preserve `.local/inventory.json` until cleanup completes. Shared GCP project/APIs and other apps' bucket prefixes survive. Disable GitHub `ENABLE_SYNTHETICS` separately; no GitHub writes are made by setup.

References: [Quarkus telemetry](https://quarkus.io/guides/opentelemetry/), [OpenTelemetry Java SDK](https://opentelemetry.io/docs/languages/java/sdk/), [Cloud Run Pub/Sub authentication](https://docs.cloud.google.com/run/docs/tutorials/pubsub), [Cloud Run Scheduler authentication](https://docs.cloud.google.com/run/docs/triggering/using-scheduler).
