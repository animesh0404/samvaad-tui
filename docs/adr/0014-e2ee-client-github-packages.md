# ADR 0014: E2EE Client from GitHub Packages

- Status: Accepted, amended (see Amendment below; original interim decision preserved as history)
- Date: 2026-09-27
- Amended: 2026-09-28

## Current state

The historical GitHub Packages decision below is retained for provenance only. The final and current dependency is Maven Central: io.github.animesh0404:e2ee-client:0.1.0. No GitHub Packages credentials, repository, or sibling checkout are required by the current TUI build.

## Context

The TUI consumed the reusable JVM E2EE client library
(`com.samvaad:e2ee-client`) from the standalone `samvaad-e2ee-lib`
repository through a temporary composite build
(`includeBuild('../samvaad-e2ee-lib')` with an explicit
`dependencySubstitution` rule in `settings.gradle`, because the library
repository's root project is named `samvaad-e2ee-lib` while its artifact
remains `e2ee-client`). The library is now published as
`com.samvaad:e2ee-client:0.1.0` (library tag `v0.1.0`) to GitHub Packages,
and `samvaad-server` already consumes that published artifact.

## Decision

Retire the temporary composite-build consumption. The TUI consumes the
published GitHub Packages artifact with the unchanged coordinate:

```groovy
implementation 'com.samvaad:e2ee-client:0.1.0'
```

- Repository:
  `https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`
  (see `samvaad-e2ee-lib` ADR 0002 for why GitHub Packages is used
  instead of Maven Central). No sibling checkout is required for normal
  builds.
- No Java source, E2EE API usage, dependency-version, or architectural
  change: this is a repository-resolution change only (composite
  `includeBuild` + substitution removed from `settings.gradle`; Maven
  repository declared in `build.gradle`).
- Credentials (local development and CI): GitHub Packages Maven requires
  authentication even for reads; anonymous resolution is not assumed to
  work. `build.gradle` reads `GITHUB_ACTOR` / `GITHUB_TOKEN` from the
  environment only — no credentials are hardcoded or committed, and no
  local-file credential mechanism is used in this project. Provide any
  GitHub username as `GITHUB_ACTOR` and a personal access token with
  `read:packages` scope as `GITHUB_TOKEN` (in GitHub Actions the
  repository-provided `GITHUB_TOKEN` works as-is). See
  `docs/DEVELOPMENT.md`.
- License / source availability: the consumed library is published as
  AGPL-3.0-only (see its `LICENSE`, per-file
  `SPDX-License-Identifier: AGPL-3.0-only`, and published POM), and it
  links `org.signal:libsignal-client:0.86.5` (AGPL-3.0-only; no Signal
  source vendored). The corresponding source for the exact consumed
  artifact is `https://github.com/animesh0404/samvaad-e2ee-lib` at tag
  `v0.1.0`. Downstream distribution must preserve the already-identified
  AGPL source-availability implications (ADR 0005 records that snapshot
  and vault formats/versioning are owned by the library, never by the
  TUI); nothing in this amendment re-licenses the TUI.

## Consequences

- The TUI keeps byte-identical E2EE behavior while compiling against the
  published library; its full suite must stay green.
- Local builds require `GITHUB_ACTOR` / `GITHUB_TOKEN` exported before
  any Gradle command that resolves dependencies.
- `e2ee-client` versioning (`0.1.0`) remains the shared contract
  coordinate with the server.

## Amendment (2026-09-28): E2EE client from Maven Central (final)

The interim GitHub Packages consumption recorded above is retired. The
TUI now consumes the publicly published Maven Central artifact:

```groovy
implementation 'io.github.animesh0404:e2ee-client:0.1.0'
```

- Final coordinate: `io.github.animesh0404:e2ee-client:0.1.0` (library
  tag `v0.1.0`; previously `com.samvaad:e2ee-client:0.1.0`).
- Repository: Maven Central, resolved through Gradle's existing
  `mavenCentral()` configuration. No GitHub Packages repository
  (`https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`) is
  configured anymore.
- Credentials: Maven Central resolution requires no GitHub credentials.
  `GITHUB_ACTOR` / `GITHUB_TOKEN` are no longer required for this
  dependency, in local development or CI.
- The temporary composite-build approach (`includeBuild` +
  substitution) remains retired, as decided above.
- The final dependency is a normal external Maven dependency; its
  transitive runtime dependency
  (`org.signal:libsignal-client:0.86.5`) resolves from Maven Central
  with it.
- No Java source, E2EE API usage, or architectural change: this is a
  repository-resolution and coordinate change only, verified by
  `dependencyInsight` / `dependencies` on `runtimeClasspath`. The
  AGPL-3.0-only / source-availability position stated above is
  unchanged.
