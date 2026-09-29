# Minimum multi-agent workflow

These instructions apply to the entire repository. Use `docs/PROJECT_STATUS.md` as the project
status source of truth and `docs/CODEX_WORKFLOW.md` for the longer delegation contract.

## Roles

- Sol is the lead agent. Sol owns ambiguous requirements, architecture, prioritization, security
  decisions, integration, final review and communication with the user.
- `luna_worker` handles clear, bounded, repeatable or high-volume subtasks whose results can be
  independently checked.
- Sol remains accountable for every delegated result. A Luna result is not complete until Sol has
  inspected its diff/evidence and rerun proportionate verification from the main working tree.

## Required bounded contract

Before delegating to Luna, Sol must state all of the following:

1. Exact objective and expected behavior or artifact.
2. Exact files/components Luna may read or change.
3. Files, behaviors and decisions Luna must not touch.
4. Exact verification commands or inspections that must pass.
5. Required return format: files changed, behavior delivered, verification results, blockers and
   residual risks.

If any of these are materially ambiguous, Sol must resolve the ambiguity first. Luna must not widen
scope or make undelegated product/architecture decisions.

## Delegation policy

- Prefer Luna for mechanical implementation, focused tests, repetitive migrations, narrow scans,
  log analysis and documentation updates with objective acceptance checks.
- Keep cross-cutting design, security/trust policy, hardware claims, destructive operations,
  conflict resolution and final acceptance with Sol.
- Parallelize independent read-heavy work when useful. Avoid overlapping write scopes; assign one
  owner per file/component or sequence the changes.
- Use the smallest useful reasoning effort. The personal `luna_worker` default is the user's
  requested `max`; an explicit low/medium override is appropriate for simple work when the current
  Codex surface supports it.
- Do not delegate merely to create activity. Small tasks with no useful separation may stay with
  Sol.

## Agent configuration safety

The personal custom agent is `%USERPROFILE%\.codex\agents\luna-worker.toml` and uses
`name = "luna_worker"`, `model = "gpt-5.6-luna"` and `model_reasoning_effort = "max"`.

Before changing that file, Sol must preserve existing settings, verify compatibility with the
installed/current Codex schema, show the proposed diff, apply only the reviewed change, parse the
TOML and smoke-test `luna_worker`. Never rewrite agent configuration silently.

## Project safeguards

- Preserve unrelated user changes and do not stage `DEV_ENVIRONMENT_REQUIREMENTS.md` unless the
  user explicitly requests it.
- Vercel builds and deploys the Minimum portal only from the production branch `main`. A request to
  change the Minimum WebUI is not complete at branch/PR validation alone unless the user explicitly
  asks for local-only work: run the `web/` checks, merge the reviewed change into `main`, monitor the
  resulting Vercel production deployment, and smoke-check `https://minimum.vra.or.th/`.
- Never commit Mumble access tokens, private certificate fingerprints, credentials or unsanitized
  device data.
- Do not claim T99/T56 screen-off hardware PTT support without a real device trace.
- Keep the existing Mumla/Humla protocol and service-owned PTT safety path intact unless Sol has
  explicitly approved a bounded change.
- After completing the user's requested batch, Sol must re-check the Technical Brief gap analysis,
  select the highest-value unblocked bounded slice, and continue it when safe and within the user's
  existing authority. Hardware actions, transmissions and destructive tests retain their normal
  evidence and authorization boundaries.
