# Neko hosting research

Researched 2026-10-01. These are recommendations for brainstorming, not a deployment decision. Recheck pricing and limits before implementation.

## Confirmed requirement

The user prioritizes a proactive personal agent: react to transactions and run scheduled checks when the Android interface is closed. Keep durable memory, user-authorized responsibilities, task history, retry state, and reviewable proposals. Minimize recurring expense, ideally zero. Android remains Kotlin; the backend language is open.

This workload can use event-triggered and scheduled execution. Persistent agent responsibilities live in storage between runs; no continuously running model loop or VM is necessary. This is an architectural recommendation, not a guarantee of uninterrupted service.

## Candidates

| Option | Ongoing hosting cost | Fit and limits |
| --- | --- | --- |
| Cloudflare Workers Free + D1 + Cron Triggers | $0 within quotas | Leading candidate for a small service handling events, persistent tasks, schedules, and optional OpenRouter calls. Free Workers: 100,000 requests/day, 10 ms CPU/invocation, 128 MB RAM, five cron triggers/account. D1: 5 GB storage, 5 million rows read/day, 100,000 written/day. A small TypeScript backend is the straightforward route; this is not a Kotlin/JVM container host. |
| Google Cloud Run + Cloud Scheduler + a database with free allowance | Potentially $0 at personal usage, not guaranteed | Suitable for a Kotlin/JVM backend container. Request-based billing, minimum instances zero, durable state outside the container, external scheduler to wake it. Free allowance includes 2 million requests/month plus CPU/memory quotas; Scheduler includes three jobs/billing account. Requires a billing account. Database, image storage/builds, secrets, logs, networking, and regional prices must also fit their respective allowances. |
| Supabase Free + Edge Functions + database schedules | $0 within quotas | Convenient PostgreSQL/auth option for eventual shared groups. Free database is 500 MB; 500,000 function invocations/month. Free projects can pause after low activity over seven days. Functions have 150-second wall time and 2-second CPU limits. Less attractive if unattended operation through quiet periods is essential. |
| Oracle Always Free VM | $0 for eligible resources within allowances | Can host a Kotlin backend, database, and worker. However, capacity may be unavailable and idle compute can be reclaimed. Current documentation lists two total A1 OCPUs and 12 GB RAM for Always Free tenancies; do not repeat older 4-OCPU/24-GB figures. More operational responsibility and a poor primary choice for a quiet personal agent. |
| Fly.io | Paid usage | Good container/worker option if a modest recurring bill becomes acceptable. Regional compute, storage, and networking prices apply. Auto-stop/suspend can reduce compute expense; an external trigger must wake a stopped machine for scheduled work. Do not treat a tiny-machine estimate as the total app bill. |
| Blaxel | Metered active execution and storage | Stateful sandboxes can suspend and resume quickly. Current sandbox compute rate is $0.0000115 per allocated GB RAM-second; snapshot storage is $0.20/GB-month. Useful when an agent needs its own execution environment. Optional for this event/schedule workload, rather than a necessary first expense. |

Cloudflare network waits are distinct from CPU time, so a model taking seconds does not automatically violate the 10 ms CPU quota. Authentication, serialization, and agent orchestration still need measurement. Cron invocations have a 15-minute wall-time limit. HTTP background work after disconnect/response has a 30-second `waitUntil` window: save tasks durably rather than relying on an untracked fire-and-forget model call. Quota exhaustion can cause failed requests; use persisted retry state, a recovery sweep, and visible capture/sync status.

## Proposed economical split

- Phone: SMS parsing, authoritative personal ledger, deterministic categories and budgets, local notifications, direct authorized Splitwise access, offline drafts. Keep raw SMS and Splitwise credentials local under the user's preferred connection mode.
- Backend: authenticated events containing only authorized structured fields, persistent goals/task state, scheduled checks, bounded retries, activity history, model secrets, optional model calls, and push requests. Protect endpoint access and constrain tool/model usage even on a free plan.
- Cloud schedules can run when the phone is offline, but can only reason over previously synced authorized data. Record a last-sync timestamp; never imply the cloud can read new phone SMS or use a local-only Splitwise credential while the phone is unavailable.
- Use local rules and computed reports before calling a model. Free hosting does not make arbitrary model usage free. A free-model-only setting must never silently fall back to a paid endpoint.
- Shared groups remain a v1 requirement; their authoritative shared state and conflict rules need separate design. A local personal ledger alone does not satisfy group synchronization.

WorkManager periodic work has a minimum 15-minute interval and timing depends on Android constraints/system optimization. It is useful for eventual sync and phone-side checks, not an exact clock. Firebase Cloud Messaging is no-cost, but delivery may be delayed; normal priority is delayed in Doze and high priority is intended for urgent user-visible notifications. Permissions, connectivity, force-stop, and device battery policies still affect phone behavior. No hosting provider can eliminate those limits.

## AI cost

OpenRouter currently lists 50 requests/day on its Free plan. Free endpoints have low rate limits and changing availability. Model tools may require multiple requests per task. Start with a bounded allowance such as at most ten automatic model requests/day, optional summaries, and a free-only allowlist. This is a proposed product limit, not an approved value. If suitable providers cannot meet privacy requirements or are unavailable, retain drafts and provide computed summaries. Provider retention/training policies differ; free price alone is insufficient for selecting a finance-data endpoint.

For scale intuition, a proposed scenario with 20 transaction events/day, one hourly scheduling sweep, and five chats/day produces about 1,470 primary invocations per 30 days before retries, notifications, and tool turns. Most events should need no LLM. Actual CPU, database rows scanned, tokens, and privacy compatibility still need validation.

## Primary sources

- [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/) and [limits](https://developers.cloudflare.com/workers/platform/limits/).
- [Cloudflare Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/) and [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/).
- [Google Cloud free allowances](https://docs.cloud.google.com/free/docs/free-cloud-features), [Cloud Run minimum instances/billing](https://docs.cloud.google.com/run/docs/configuring/min-instances), and [Scheduler pricing](https://cloud.google.com/scheduler/pricing).
- [Firebase pricing, including FCM and Firestore](https://firebase.google.com/pricing).
- [Supabase billing/quotas](https://supabase.com/docs/guides/platform/billing-on-supabase), [project pausing](https://supabase.com/docs/guides/platform/free-project-pausing), and [function limits](https://supabase.com/docs/guides/functions/limits).
- [Oracle Always Free resources and reclamation](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm).
- [Fly resource pricing](https://docs.fly.io/about/pricing/) and [autostop/autostart](https://docs.fly.io/launch/autostop-autostart/).
- [Blaxel pricing](https://blaxel.ai/pricing).
- [Android work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) and [FCM delivery priorities](https://firebase.google.com/docs/cloud-messaging/android-message-priority).
- [OpenRouter pricing](https://openrouter.ai/pricing), [free-tier limitations](https://openrouter.ai/support/), and [provider data practices](https://openrouter.ai/privacy/).
