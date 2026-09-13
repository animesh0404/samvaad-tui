# ADR-0006: Direct Lanterna Screen and Explicit UI Boundary

- Status: Accepted
- Date: 2026-09-14

## Context

Phase 3 needs a fullscreen terminal UI while keeping the TUI thin, testable, and independent from HTTP/realtime protocol mechanics. Lanterna provides the terminal primitives required for rendering, keyboard input, alternate-screen lifecycle, and terminal restoration.

The project does not need a second GUI framework or a large UI abstraction layer for the V1 shell. It also needs a clean seam so bootstrap can launch the UI without passing authentication credentials or tokens into presentation code.

## Decision

Use Lanterna `3.1.5` directly through its `Screen`/terminal APIs. Do not introduce Lanterna's higher-level GUI framework or another UI framework unless a concrete requirement later justifies it.

Keep the UI behind an explicit `TuiLauncher` boundary:

- `TuiLauncher` is the bootstrap-facing launch seam.
- `TuiApp` owns terminal lifecycle and the render/input loop.
- `TuiController` maps keyboard input to UI state transitions.
- `TuiRenderer` owns presentation.
- UI view records/state contain display data only.

The authenticated access and refresh tokens remain in the authentication/session/bootstrap layers and are not passed to the TUI.

## Consequences

Positive:

- small dependency and abstraction footprint;
- direct control over terminal behavior and cleanup;
- clear separation between presentation and server transport;
- controller/state logic can be unit tested without a real terminal;
- future conversation/realtime integrations can feed UI state without coupling the renderer to protocols.

Negative:

- the project owns more rendering/input-loop code than it would with a GUI framework;
- richer widgets and interaction patterns may require additional code later.

The decision can be revisited if the TUI grows beyond what a small direct-Screen architecture can reasonably support.
