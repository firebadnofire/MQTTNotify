# AGENTS.md

This file defines expectations and constraints for AI agents interacting with this repository via MCP or similar tooling.

The goal is to preserve the project’s scope, security model, and design philosophy while allowing limited, targeted automation.

---

## Project intent

This repository contains an Android application that converts **MQTTS messages into Android notifications**.

It is intentionally minimal, client-only, and fully self-hosted in its operational model.

AI agents must treat this project as:

* A **notification sink**, not a platform
* A **power-user tool**, not a consumer app
* A **client for existing MQTT infrastructure**, not a replacement for it

Any contribution that shifts these boundaries is out of scope.

---

## Non-negotiable constraints

AI agents MUST NOT:

* Introduce cloud dependencies of any kind
* Add Firebase, FCM, or Google Play Services
* Add server-side components or companion services
* Add analytics, telemetry, or tracking
* Add user accounts or identity systems
* Add message persistence, history, or sync layers
* Attempt to bypass or hide Android background execution limits

If a requested change conflicts with any of the above, the agent must refuse.

---

## Security requirements

AI agents MUST:

* Enforce TLS for all MQTT connections
* Preserve support for client certificate authentication
* Avoid introducing insecure defaults
* Store secrets only via Android Keystore

Security simplifications are not acceptable, even for convenience.

---

## Scope discipline

Allowed areas of change:

* Bug fixes
* Battery and network efficiency improvements
* UI clarity improvements (not UI expansion)
* Documentation updates
* Build system maintenance
* Dependency updates with equivalent security guarantees

Disallowed expansions:

* Multi-device coordination
* Cloud fallback mechanisms
* "Reliability" layers that imply server involvement

---

## Android-specific guidance

* Foreground services are expected and must remain explicit
* Battery optimization exemptions must be documented, not automated or hidden
* Doze and OEM task killing behavior must be acknowledged honestly
* Background workarounds that rely on undocumented APIs are prohibited

---

## MQTT-specific guidance

* MQTT remains the sole transport
* Topic subscription logic must remain explicit and user-defined
* QoS-to-notification mapping is intentional and should not be generalized
* Payload parsing must be strict and predictable

Avoid adding broker-specific behavior unless strictly optional and well-isolated.

---

## Code style and quality

AI agents SHOULD:

* Prefer clarity over cleverness
* Minimize abstraction layers
* Avoid unnecessary dependencies
* Match existing formatting and architectural patterns
* Keep the codebase auditable by a single human

Large refactors require clear justification.

---

## Documentation expectations

Any behavioral change MUST be reflected in:

* README.md
  n- Relevant inline documentation

Documentation must describe tradeoffs explicitly.

---

## Refusal policy

If an instruction would:

* Violate project intent
* Weaken security guarantees
* Expand scope beyond stated goals

The agent must clearly refuse and explain why.

---

## Summary

This project values:

* Explicit tradeoffs
* Honest constraints
* User-owned infrastructure
* Small, inspectable systems

AI agents are expected to act accordingly.
