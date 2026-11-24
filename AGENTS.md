# Slopify

Slopify is a self-hosted music streaming project. Prioritize security, performance, clean code, and simplicity.

## Repository layout

- `server/` contains the Rust backend.
- `client/` contains the Kotlin Multiplatform mobile and desktop applications. Android and Linux desktop are the primary targets. Other platforms are secondary.
- Keep server and client builds and releases independent. Changes that span the API and its client can land together in one pull request.

## Working on the project

- Get the maintainer's approval before adding a dependency or choosing or changing any part of the technology stack. When proposing a choice, explain the need and the relevant trade-offs in security, performance, maintainability, and complexity.
- Use the simplest implementation that meets the task requirements. Reuse existing code and approved dependencies where they fit.
- Verify changes with the relevant checks for each affected component. For client changes, check Android and Linux desktop when the behavior is shared or the change affects both targets.
