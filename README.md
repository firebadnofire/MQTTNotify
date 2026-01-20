# mqtt-notify-android

An Android app that turns **MQTTS messages into system notifications**.

No cloud. No middlemen. No servers beyond the MQTT broker you already run.

This is **not** a general-purpose MQTT client. It is a notification sink for people who already use MQTT as an event bus.

---

## What this is

* Maintains a persistent **MQTTS** connection
* Subscribes to explicitly configured topics
* Posts Android notifications when messages arrive
* Fully self-hosted end to end

If you can `mosquitto_pub`, you can notify your phone.

## What this is not

* Not a replacement for Firebase Cloud Messaging
* Not guaranteed delivery if Android kills the process
* Not a dashboard, log viewer, or message archive
* Not designed for casual users or default Android setups

> If you want something that “just works” without touching battery settings, use ntfy.sh instead.

---

## Design principles

* **MQTT-native**
  The broker is the platform. Authentication, ACLs, and TLS are delegated entirely to it.

* **No cloud dependencies**
  No Google services, no relay servers, no telemetry.

* **Foreground service by design**
  The app is honest about needing to stay alive.

* **Minimal surface area**
  Small codebase, predictable behavior, no feature bloat.

---

## Requirements

* Android 8.0+
* Notifications permission enabled (Android 13+)
* Battery optimization **disabled** for this app
* A reachable MQTT broker with TLS enabled

OEMs with aggressive task killing (Samsung, Xiaomi, OnePlus) may require additional steps.
See: [https://dontkillmyapp.com](https://dontkillmyapp.com)

---

## Supported brokers

Known to work with:

* Mosquitto
* EMQX
* VerneMQ

Anything compliant with MQTT 3.1.1 or 5.0 over TLS should work.

---

## Authentication

Supported methods:

* Username + password
* Client TLS certificates (recommended, selected from the system KeyChain)

Anonymous or plaintext connections are intentionally unsupported.

---

## Message format

Messages may be **plain text** or **JSON**.

### Plain text

```text
Backup completed successfully
```

This becomes the notification body. The topic name is used as the title.

### JSON payload

```json
{
  "title": "Backup Job",
  "body": "Nightly backup completed",
  "priority": "high",
  "tag": "backup-status"
}
```

Supported fields:

| Field      | Description                                  |
| ---------- | -------------------------------------------- |
| `title`    | Notification title                           |
| `body`     | Notification body                            |
| `priority` | `low`, `normal`, `high`, `urgent`            |
| `tag`      | Replaces previous notification with same tag |

Unknown fields are ignored.

---

## MQTT QoS mapping

| MQTT QoS | Notification behavior                   |
| -------: | --------------------------------------- |
|        0 | Best-effort, silent/info                |
|        1 | Standard notification                   |
|        2 | Urgent notification (sound + vibration) |

This mapping is intentional and opinionated.

---

## Topic subscriptions

* Subscriptions must be explicitly defined
* Wildcards are supported but discouraged
* Each topic can have independent notification rules

Examples:

```text
alerts/backup
alerts/ci/failed
home/doorbell
alerts/backup:2
```

Optional `:qos` suffixes apply to a single subscription (0, 1, or 2). If omitted, the app uses QoS 1.

---

## Battery and connectivity behavior

* Uses a persistent foreground service
* Reconnects automatically with exponential backoff
* Adapts keepalive based on network type
* No polling, no wakeups when idle
* Prompts you to disable battery optimization if it is enabled

If the app is killed, messages published during that time are **not** replayed.

---

## Security model

* The MQTT broker is the trust root
* TLS is mandatory
* Credentials are stored using Android Keystore-backed encryption
* No analytics, no external connections

If you do not trust your broker, do not use this app.

---

## Installation

For now:

* Build from source
* Install via APK

F-Droid metadata is planned once the project stabilizes.

---

## Use cases

* Backup job notifications
* CI failure alerts
* Home automation events
* Cron job monitoring
* Self-hosted service health checks

If it can publish MQTT, it can notify your phone.

---

## Non-goals

* Message history or persistence
* Multi-device synchronization
* Server-side filtering logic
* Push reliability guarantees under Doze

These are tradeoffs, not omissions.

---

## License

MIT

---

## Philosophy

Infrastructure is real, physical, and owned by someone.

This app assumes that someone is you.
