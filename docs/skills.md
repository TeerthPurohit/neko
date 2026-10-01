# Installed skills for Neko

Installed on 2026-10-01 into `C:/Users/teert/.codex/skills`, using Codex's Skill Installer helpers. Each package was copied from a pinned GitHub revision and every copied file was checked against the downloaded source. This verifies installation integrity, not the correctness of every recommendation in a skill. No bundled skill scripts were executed.

There are 60 installed top-level packages, with three additional nested skills in the Compose kit. Impeccable was already available at `C:/Users/teert/.agents/skills/impeccable` and was not replaced.

The machine did not expose a `codex` executable in PATH, so the CLI plugin-marketplace method was not used. These are file-based skill installations, not marketplace plugin registrations. Newly installed skills should be available on the next turn; if the catalog does not refresh, start a fresh thread or restart Codex.

## Sources and scope

| Source | Installed packages | Intended use |
| --- | --- | --- |
| [Interface Design](https://github.com/Dammyjay93/interface-design) | `interface-design` | Product interfaces, tokens, component consistency, and `.interface-design/system.md` memory |
| [Taste Skill](https://github.com/Leonxlnx/taste-skill) | `design-taste-frontend` | Upstream's current Taste Skill; landing pages, portfolios, and redesigns within its stated scope |
| [Vercel Agent Skills](https://github.com/vercel-labs/agent-skills) | `web-design-guidelines`, `vercel-react-native-skills` | Web audits and React Native/Expo respectively; available for those surfaces, not Kotlin component imports |
| [Android Mobile Design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-android-design) | `mobile-android-design` | Native Android/Compose UI, Material patterns, adaptive layouts, and accessibility |
| [Official Kotlin Agent Skills](https://github.com/Kotlin/kotlin-agent-skills) | All 10 catalog skills | Kotlin tooling, migrations, and JPA mapping when applicable |
| [Compose Kotlin Agent Skills](https://github.com/haidrrrry/compose-kotlin-agent-skills) | Full root bundle plus its three nested references | Android/Kotlin architecture, Compose UI, and tests; original folder structure retained for relative references |
| [Chris Banes' Skills](https://github.com/chrisbanes/skills) | All 19 catalog skills | Kotlin API/concurrency, Compose state/components/animations/performance/tests, Gradle, and explicit delivery workflows |
| [Kotlin Backend Agent Skills](https://github.com/yalishevant/kotlin-backend-agent-skills) | All 25 catalog skills | Kotlin/Spring backend context, APIs, persistence, transactions, security, tests, and operations |

Exact source paths, commit IDs, installed paths, SHA-256 values for each entrypoint, verified file counts, and nested skill locations are in [skills-lock.json](skills-lock.json).

## Application rules

Neko is still being designed. Skill installation does not decide the UI framework, MVI versus MVVM, build versions, backend language/framework, cloud provider, or deployment topology.

Use focused skills on matching tasks. Keep the user's reference images and chosen product behavior authoritative. Android uses native dimensions and platform behavior; web or React Native guidance does not translate directly into Kotlin components.

The community Compose kit contains opinionated defaults and version examples that need verification before use, including a Navigation 3 claim alongside a Navigation 2.9 artifact example. The backend catalog is a small community source, installed at the user's request. Both remain guidance subject to official documentation and project decisions.

Upstream Taste Skill currently excludes dashboards, data tables, and multi-step product UI. It is installed as requested; Interface Design and Impeccable are the better primary matches for Neko's ledger and app screens.

Installation does not run skill workflows, publish releases, deploy services, or configure third-party accounts.
