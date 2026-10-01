# Corrections and focused agents

Neko now saves explicit user corrections in Postgres and consults them on future
AI tasks, including after a restart. This is persistent preference memory; model
weights are not retrained. Model adherence is not a guarantee that an error can
never recur. Ambiguous or conflicting rules require clarification.

In the existing Android conversation, send:

- `Remember for next time: Only mention a budget alert at 90 percent.`
- `Show learned rules`
- `Forget rule: <the rule ID from the list>`

The first command explicitly approves saving the corrected behavior. A complaint
alone does not establish the intended replacement behavior. Save the replacement
once it is clear. Chat commands save rules for all tasks; a dedicated API can
target one specialist and retain the original flagged behavior.

Authenticated API:

- `GET /v1/agent/corrections` lists this user's rules.
- `POST /v1/agent/corrections` accepts `behavior`, `correction`, `scope`, and
  `confirmed: true`. Scopes: `all`, `classification`, `ledger`, `budget`,
  `summary`, `followup`, `chat`.
- `DELETE /v1/agent/corrections/<id>` forgets a rule for this user.

Remove a conflicting rule and save its replacement. Memory is limited to 30 rules
and 1,000 characters per correction. Deleting cloud context also removes learned
rules. Treat saved rules as personal cloud data; avoid sensitive bank credentials
or raw SMS. Saving memory makes no model call.

A coordinator routes each chat task to a small focused LangGraph/Nanobot agent:

| Agent | Responsibility | Allowed tools |
| --- | --- | --- |
| Ledger | Transaction review and category drafts | Summary, explain transaction, draft category change |
| Budget | Budget progress | Summary, budgets, explain transaction |
| Summary | Spending, income, reimbursements | Summary, explain transaction |
| Follow-up | Authorized scheduled check | Summary, budgets, explain transaction |
| Chat | General finance conversation | Summary, budgets, explain transaction |
| Classification | Category suggestions through the existing Jev path | Structured category decision |

One specialist runs per task to keep calls bounded. Scheduled jobs route directly
to follow-up; chat routing uses task keywords and can be refined later. Broad
requests currently use one specialist rather than running several model loops.
All specialists share the user's explicit global corrections and receive their
own scoped corrections. Tool registries, requests, and proposals are isolated per
run. Learned preferences cannot grant new tool permissions. Ledger changes remain
reviewable drafts; shared expense writes still require confirmation.

Apply `003_agent_learning.sql` with the existing migration runner before running
the updated backend. These changes are local until migrated and deployed.
