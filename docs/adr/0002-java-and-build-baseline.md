# ADR 0002: Java 25 and Gradle Baseline

- Status: Accepted
- Date: 2026-09-14

## Context

The TUI is a new Java client and needs a current Java baseline and conventional lightweight build system.

## Decision

Use Java 25 LTS, Gradle, and the checked-in Gradle Wrapper.

The project uses Gradle's standard Java application and test facilities rather than another build system.

## Consequences

### Positive

- modern Java baseline
- conventional dependency management
- reproducible wrapper-based builds
- familiar Java tooling

### Negative

- Java 25 is required
- Gradle is part of the toolchain

## Scope

This is the baseline for the TUI. Changing it requires an explicit architectural decision.
