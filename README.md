# Android_Development

This repository is the primary Android development **monorepo** for building, testing, debugging, and producing APKs for multiple independent Android apps from one GitHub repository.

## Mandatory instructions

**Before making any change in this repository, read [AGENTS.md](./AGENTS.md) in full. Compliance with `AGENTS.md` is compulsory for all agents, assistants, contributors, scripts, and automated development workflows.**

Do not begin source-code, build-script, workflow, configuration, or project modifications until the requirements in `AGENTS.md` have been reviewed and are being followed.

In particular, this repository requires a **local-first development workflow**: develop and validate changes locally first, review the final diff, then commit/push coherent changes and use GitHub Actions for final Android-specific validation and APK/artifact generation.

## Repository strategy: monorepo

Use this single repository for multiple Android apps instead of creating a new GitHub repository for every experiment, utility, prototype, or personal app.

New apps should normally live under:

```text
Android_Development/
├── AGENTS.md
├── README.md
├── .github/
│   └── workflows/
├── apps/
│   ├── AppOne/
│   ├── AppTwo/
│   └── AppThree/
└── shared/
```

Each directory under `apps/<AppName>/` should be treated as an independent Android application workspace with its own:

- Gradle project/configuration as appropriate;
- application/package ID;
- versioning;
- source and resources;
- app-specific documentation when useful;
- diagnostics/testing configuration when needed;
- APK/artifact output;
- optional app-level `AGENTS.md` for rules that apply only to that app.

Shared code or tooling that is genuinely useful to multiple apps may live under `shared/`. Do not move app-specific code into `shared/` merely for convenience.

## CI behavior

GitHub Actions should be scoped so that changing one app does not unnecessarily build every other app.

Prefer path-filtered workflows such as:

```yaml
on:
  push:
    paths:
      - "apps/ExampleApp/**"
```

A workflow may also be reusable/shared where that reduces duplication without coupling unrelated apps.

## When to use a separate repository

Keep experiments, prototypes, personal utilities, and small-to-medium independent apps in this monorepo by default.

Consider extracting an app into its own GitHub repository when it becomes a substantial standalone project with needs such as:

- a separate release lifecycle;
- independent collaborators or permissions;
- separate issues/project management;
- app-specific secrets or CI infrastructure;
- independent public/private visibility;
- substantial long-term maintenance;
- a need for completely independent repository history.

An app can be extracted later if necessary, so creating a separate repository at the beginning is not required.

## Preferred development flow

**Read AGENTS.md → select the target app under `apps/` → prepare locally → validate locally → inspect final diff → commit coherent batch → build/test in GitHub Actions → fix any CI-specific issue locally → produce APK/artifact.**
