# What happens when Neko is called

1. Android parses transaction SMS locally. Chat, synced draft transactions, and
   standing responsibilities create durable backend tasks.
2. The backend authenticates the user, checks AI consent, and reserves the turn's
   cost allowance before calling the private agent service.
3. The coordinator chooses one focused task agent. It loads the synced ledger,
   recent conversation, budgets, and applicable saved correction rules.
4. With `auto` selected, Neko refreshes OpenRouter's public catalog every five
   minutes. It filters for paid text models with tool calls, a context of at least
   16,000 tokens, input prices at most $0.50/M and output prices at most $1/M.
   Free variants, batch variants, router models, and aliases are excluded.
5. A bounded shortlist contains up to 28 models with the lowest estimated cost
   for a 4,000-input/1,000-output workload, plus the four requested reference models
   when eligible. Jev receives the task, two short recent messages, candidate
   descriptions, prices, and published benchmarks when available. It picks the
   least costly adequate candidate. This is a custom router using Jev's Decisions
   API, not the hosted `typesafe/jev-router` endpoint.
6. The specialist runs the chosen model directly through OpenRouter. It can read
   computed summaries and its assigned tools. At most three generation calls are
   allowed; output is capped at 4,096 tokens per call. Provider prices are capped
   on every call, and a conservative input/output estimate must fit the remaining
   turn allowance before a call starts. The default turn allowance is $0.06.
7. The backend records actual reported cost, selected model and routing source,
   then saves the answer and any reviewable proposals. Timeouts conservatively
   consume their reservation because a provider may already have billed.
8. Android fetches the result. Category changes are drafts until confirmed.

Jev routes once per task; it does not switch models between tool calls. Low
confidence, invalid selections, or Jev outages use the cheapest shortlisted model
instead of automatically escalating. A catalog outage uses the checked reference
pool with provider price caps still enforced. Model choice is a probabilistic
decision, not a guarantee of best quality or current provider latency.

Reference candidates are `openai/gpt-oss-120b`,
`deepseek/deepseek-v4.1-flash`, `xiaomi/mimo-v2.6-pro`, and
`openai/gpt-6-luna`. More affordable models can be chosen automatically. Manual
selection remains available. Existing accounts must select `auto` in Settings.
No ledger or transaction text is sent to the catalog endpoint. Jev's routing
request includes the user's question and two abbreviated conversation messages;
the generation model receives the consented structured context. Raw SMS stays
on-device. Memory commands and ledger arithmetic run without a generation model.

Classification still uses Jev directly for typed category suggestions. It does
not invoke a chat model simply to return a category.

## Deployment and credentials

The Fly backend needs `DATABASE_URL`, `NEKO_PAIRING_SECRET`,
`MODEL_KEY_ENCRYPTION_KEY`, and `AGENT_SERVICE_TOKEN`. Each user supplies their own
OpenRouter key through the app; the server stores it encrypted. Configure an
OpenRouter account/key spending limit as the provider's billing ceiling.

Firebase client configuration and service-account credentials are needed for
server push notifications. Without them, results are available when the app
fetches activity; local SMS review notifications still work.

Scheduled work runs when the Fly machine is awake. The repository's GitHub Actions
wake workflow calls a health URL held in the `NEKO_WAKE_URL` repository secret
twice per hour. Startup runs the durable scheduler. This allows closed-app checks
while retaining scale-to-zero between wakes. Delivery is approximate: checks can
wait for the next wake, and GitHub scheduled workflows can be delayed. Push still
requires Firebase credentials and client configuration.

Sources checked 2026-10-02:

- [OpenRouter Jev Decisions documentation](https://openrouter.ai/docs/guides/community/jev)
- [Hosted Jev Router restrictions and include-list fallback](https://openrouter.ai/docs/guides/routing/routers/jev-router)
- [Provider price ceilings](https://openrouter.ai/docs/guides/routing/provider-selection)
- [Live model catalog](https://openrouter.ai/api/v1/models)
