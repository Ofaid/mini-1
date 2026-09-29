## Outcome

Describe the user-visible behavior and why this change is needed. Link the issue with `Closes #...` when applicable.

## Scope

- Components changed:
- Explicitly out of scope:
- Configuration schema or migration impact:

## Verification

- [ ] Relevant automated tests pass.
- [ ] `:app:testFossDebugUnitTest :app:assembleFossDebug` passes for Android changes.
- [ ] Web tests, TypeScript and production build pass for portal changes.
- [ ] Real-device acceptance evidence is linked when hardware behavior changed.
- [ ] Documentation and hand-off status are updated.

List exact commands and manual scenarios with their results:

## Safety and privacy

- [ ] No server password, access token, bearer token, APRS passcode, signing material, exact private coordinates, or unsanitized device data is committed or attached.
- [ ] PTT changes preserve release-on-key-up, network loss, disconnect, service destruction and watchdog behavior.
- [ ] Reconnect/config changes preserve throttling, last-known-good fallback and selected-channel restoration.
- [ ] Public location behavior and operator consent were reviewed when tracking changed.

## Release impact

- [ ] No release note required.
- [ ] Release note or known limitation is included below.

Release note / known limitation:
