# Codex worker workflow

The executable project-wide rules are in the repository-root `AGENTS.md`. Codex loads that file as
durable project guidance; this document provides the human-readable hand-off detail.

The personal custom agent used for this project is:

```text
%USERPROFILE%\.codex\agents\luna-worker.toml
name = luna_worker
model = gpt-5.6-luna
model_reasoning_effort = max
```

The personal file is intentionally not committed. It must contain `name`, `description`, and
`developer_instructions`; the local Codex manual also allows `model` and
`model_reasoning_effort` in a standalone custom-agent file.

Use the main Sol agent for ambiguous architecture, prioritization, security decisions and integration.
Use `luna_worker` for clear repeatable work with a bounded contract. Max reasoning is the requested
default for this worker, but low/medium is normally more efficient for mechanical scans or simple
formatting when an explicit invocation override is available.

Every delegated task must state:

1. Exact repository and allowed files/components.
2. Files and behaviors that must not be touched.
3. Concrete expected behavior/deliverables.
4. Exact build/test/inspection commands.
5. Required return format: changed files, behavior, command results, blockers and risks.

The parent agent must inspect the returned diff and test evidence. A worker result is not evidence
that the feature is complete until it is integrated and verified from the main working tree.

Before any APRS/location change, read `PROJECT_STATUS.md` and `APRS_TRACKING.md`, then verify the
current encoder/manager tests. In particular, preserve the T56-only hardware gate, APRS Object form,
validated configured name with `VR-` fallback, state symbols, piggybacked Health comment and HTTPS
receipt rule. A task must not
silently restore callsign-owned station positions or `requestSingleUpdate()`; both were tried and
rejected by live-device evidence.

Prefer independent read-heavy delegation and non-overlapping write ownership. Sol must retain
cross-cutting architecture, security/trust policy, hardware capability claims and final acceptance.
After Luna returns, Sol reviews the exact diff, checks for scope drift and secrets, and reruns the
relevant tests rather than relying only on the worker's report.

At the end of each requested implementation batch, Sol re-reads the maintained Technical Brief gap
analysis and continues with the highest-value unblocked bounded slice when it is safe and already
authorized. This persistence rule does not authorize unsupervised PTT transmissions, destructive
device tests, secret publication or unsupported hardware claims.
