# Deploy and operate on GCP

Published 2026-10-11 · Updated 2026-10-11

## Signup, cost and resource choices

Use a Google Cloud account with a billed **existing** project and an authorized gcloud deployment identity. The recipes and recommendations samples can share that project and an explicitly selected Storage bucket while retaining different service accounts, topics, secrets, services and object prefixes. Setup never creates or deletes projects, changes billing accounts, disables shared APIs or removes another application's resources.

Plan **$0–2/month shared between the two GCP samples** at the small fleet workload in the parent recommendations: approximately 100,000 tiny dynamic requests per provider, <0.5 GB internet egress, a tiny fixture/config dataset, zero minimum instances and modest native logging. This is an estimate, not a billing cap. Cloud Build, regional Artifact Registry storage, Secret Manager versions/access, Storage operations, Pub/Sub, Monitoring API reads and internet telemetry export are metered. Free quotas are shared at project/billing scopes. Domain registration and Corridora hosting are excluded.

The baseline has one 512-MiB/one-CPU request-billed Cloud Run service, concurrency 8, min instances 0, max instances 2 and 20-second request timeout. JVM heap uses at most 65% of container memory. One Scheduler job covers refresh-window activity and native metric polling; the public GitHub repository provides browser execution. The two GCP apps must account for shared Scheduler/free allowances rather than multiplying them.

Use provider budget alerts at fleet $12/$16/$18 thresholds. They are advisory and delayed. Public request limits/max instances bound concurrency, not cost. Before maintaining a public fleet, an operator must add organization-specific billing export checks that disable optional exports/synthetics when their allocated budget is reached; this compact sample does not invent a generic hard-cost guard from delayed metrics.

## Actual provisioning

```sh
gcloud auth login
./setup configure
./setup deploy --plan
./setup deploy
./setup run
./setup verify
```

Choose cloud mode, project, region and prefix. Use `GCP_ACCOUNT` to select an existing authenticated account. Setup shows the active account and plan before applying changes. CI can use an existing scoped workload-identity login with `--non-interactive`; no account password, shared Demo lookup or embedded service-account key is required.

The deployment identity needs permission to enable APIs, manage the sample's Cloud Run/Pub/Sub/Storage/Secrets/Artifact Registry/Scheduler/Logging resources, act as its created identities and set relevant IAM bindings. Prefer a dedicated demo project and a reviewed provisioning role. Cloud Build uses a dedicated build identity with artifact-writer, source-prefix read and log-writer permissions. The runtime gets only the sample object-prefix access, refresh-topic publisher, single-secret accessor and optional Monitoring viewer. The push identity is separate; its email/audience are checked inside the public app.

`setup` is the executable infrastructure definition; `infra/cloudbuild.yaml`, `Dockerfile` and `infra/artifact-cleanup.json` are its build/retention configuration. It performs real gcloud create/update/deploy operations, not printed placeholder commands:

1. Enable required shared APIs; create three service accounts and a dedicated bucket, or reuse the explicitly chosen shared bucket.
2. Restrict runtime/build Storage IAM to `recommendations/<GCP_PREFIX>/`, create refresh/native-log/DLQ topics, and mount the single backend/RUM/control configuration secret.
3. Create an owned Artifact Registry repository with seven-day delete/keep-last-three policies. Submit the Docker multi-stage build with an explicit build account and source staging under the owned sample prefix.
4. Deploy the same-origin frontend/API with the bounded limits, discover its HTTPS URL and update the OIDC audience and exact-origin CSP configuration.
5. Configure authenticated refresh/native-log push subscriptions, five best-effort delivery attempts, bounded retry delays, 20-second ack deadline and one-day message retention. Install the DLQ subscription and 15-minute Scheduler tick.
6. Optionally create the native request-log sink and Monitoring read binding. Retain one active Secret Manager version after rotation.

Setup records each created resource immediately in owner-only `.local/inventory.json` and resumes after partial failure. A preexisting untracked resource name fails safely; choose another prefix instead of taking ownership. A reused bucket is marked unowned. Reuse applies to the shared project/bucket, not to silently adopting another application's service accounts or secrets. Keep the inventory as an operator artifact.

Bucket objects are small fixed control/model/heartbeat files, plus staged build sources. Review and delete old `recommendations/<GCP_PREFIX>/builds/` source archives periodically; runtime config must not receive a blanket age-based lifecycle deletion. Retained build logs are a separate provider cost. Image cleanup runs asynchronously; it may retain more versions briefly.

## Install daily browser execution

The repository ships `.github/workflows/daily.yml`; public standard GitHub runners avoid adding an always-on collector/browser VM. In the repository settings, add:

- Variables: `ENABLE_SYNTHETICS=true`, `SAMPLE_URL=<deployed HTTPS URL>`, `GCP_PROJECT=<existing project>`.
- Secrets: `DEMO_CONTROL_TOKEN`, `CORRIDORA_INGEST_KEY`, `CORRIDORA_RUM_KEY`, from the same configured operator destination. Export keys are validated as configured but only the control token is used to drive hosted HTTP control; the application already holds its backend credentials.

The 15:01 UTC run exercises the actual bad action via a fresh bounded scenario; the 15:20 watchdog checks today's successful heartbeat and replays the browser if absent. A manual workflow dispatch uses the same watchdog logic. Locally, `./setup verify --watchdog` performs the same check and replay. Failed browser artifacts expire after two days.

The provider tick observes the natural UTC incident and invokes the slow ranking action even if the browser schedule is delayed. It is **not browser evidence**. GitHub schedules can be delayed or dropped, and public repository schedules disable after 60 days without repository activity. Maintain an external monitor for missing `demo.journey.heartbeat`; use an existing persistent runner/cron if daily same-day execution must be guaranteed beyond GitHub's schedule guarantees. No local test proves a future hosted daily incident.

## Safe teardown and unverified platform assumptions

Preview with `./setup destroy --plan`. Applying teardown deletes only owned inventory resources in reverse order and preserves the project, APIs, reused bucket and unrelated prefixes. For a shared bucket, remove only `recommendations/<GCP_PREFIX>/` and the sample-specific IAM bindings. Each deployment has a different prefix, including model/control/heartbeat/build sources. Even a bucket originally created by this checkout is preserved if teardown discovers another prefix; lack of permission to inventory contents blocks bucket deletion. Disable the GitHub synthetic variable separately. If a delete fails, inventory is retained for retry.

Local validation proves Java/JS builds, actual RUM parentage, backend exporter encoding and deterministic recovery. It does **not** validate a real GCP deployment, Docker build on Cloud Build, the selected account's provisioning permissions, organization-policy compatibility with a public Cloud Run service, push OIDC certificate fetch, the Logging sink's native delivery or Monitoring data lag. Verify these in a dedicated billed project before promoting Demo. Baseline paid AI inference is disabled because no provider adapter or key is installed.

References: [Cloud Run pricing](https://cloud.google.com/run/pricing), [GCP free quotas](https://docs.cloud.google.com/free/docs/free-cloud-features), [Scheduler pricing](https://cloud.google.com/scheduler/pricing), [Cloud Run deployment](https://docs.cloud.google.com/run/docs/deploying), [authenticated Pub/Sub push](https://docs.cloud.google.com/run/docs/tutorials/pubsub), [Artifact Registry cleanup](https://docs.cloud.google.com/artifact-registry/docs/repositories/cleanup-policy), [GitHub schedule limitations](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).
