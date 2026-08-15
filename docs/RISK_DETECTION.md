# Grief risk detection (design proposal)

> **Not implemented.** Stats 0.3.2 performs no risk detection, no scoring, no alerting, and no automatic moderation. It has no short-window bucket, no notification channel, and no protection-plugin integration. This document is a design sketch for a system an operator could build in an external analysis layer. Do not read any of it as current plugin behavior.
>
> Configuration version 4 removed the unimplemented `riskDetection.*` keys. Any that remain in an older file are ignored.

"Grief" here means in-game destruction: mass block breaking, container damage, and malicious world edits. Building an early-warning system from Stats' aggregates is feasible. Staff notification plus human confirmation is far more stable than automatic punishment, given false positives, permission context, and scheduled events.

## 1. Threat model

- **Mass destruction** — a sharp spike in blocks broken over a short window, especially containers, doors, and redstone
- **Base attacks** — damage concentrated on specific types: containers, beds, doors, rails, redstone
- **Malicious WorldEdit or FAWE** — spikes in high-impact commands such as `//set`, `//replace`, and `//paste`, particularly from accounts without the corresponding permissions
- **New-account grief** — destruction concentrated shortly after a first connection

## 2. Signals available at the current hour/day resolution

These are computable from the aggregate data described in [DATA_CATALOG.md](DATA_CATALOG.md), without coordinates. They can identify hourly or daily anomalies, but cannot identify a 5- or 10-minute burst.

- **Container break spike** — container-group breaks rising sharply against a personal or server baseline
- **Destruction ratio** — `blocks_broken_total / (blocks_placed_total + 1)` abnormally high over a short window
- **WorldEdit high-risk usage** — `worldedit:set`, `worldedit:replace`, `worldedit:paste`, weighted by account age. Builder status or permission context must come from another system.
- **Command-volume anomaly** — a spike in an hourly command count. Shorter-window spam detection needs the additional design in section 3.
- **AFK disqualifier** — a high AFK ratio reduces confidence, filtering the no-real-activity false positive

## 3. Minimum additional design for real-time alerting

The current storage is hour and day oriented. Meaningful alerting needs a shorter window, on the order of 5 minutes.

Suggested approach:

- Accumulate `player × 5-minute bucket` counters in memory
- Upload every 5 minutes, consistent with the existing flush cadence
- Evaluate alerts from the in-memory counters immediately, without waiting for the upload

Persisting those buckets is optional. If permanent retention is a burden, store only the alert events and discard the 5-minute buckets.

## 4. Risk scoring

A single rule produces too many false positives. Combining several signals into a score is more workable.

Conceptual form:

```text
risk = w1*container_break + w2*block_break + w3*worldedit_ops
     + w4*new_account_bonus - w5*builder_behavior
```

- Evaluation windows: last 5 minutes plus last 30 minutes, weighted or exponentially smoothed
- Personal baseline: robust z-score against the player's own trailing 7- or 30-day median
- Server baseline: corrected by the same-hour mean and variance, to absorb events and peak periods

## 5. Gates that reduce false positives

- **Permission gates** — holders of `worldedit.*` or `fawe.*` are builders or staff and need separate thresholds or a separate monitoring tier.
- **Context gates** — integration with a protection plugin would allow weighting destruction inside protected regions higher. Even without storing coordinates, a region ID or flag could be stored as a hash or integer.
- **Schedule gates** — thresholds should be adjustable during server events, resets, and large build days.

## 6. Notification design

- **Level 1, notice** — a summary to the staff channel: player, time window, top two or three signals
- **Level 2, warning** — a suggestion to investigate, with concrete counts such as recent container breaks and WorldEdit operations
- **Level 3, risk** — optional automatic action, starting with reversible mitigations such as a temporary edit block, a cooldown, or increased logging

## 7. Limits and principles

- Early warning without coordinates or raw block logs is possible, but confident attribution is not.
- The goal is fast situational awareness and damage limitation, not identifying a culprit.
- Any automatic sanction must ship together with its false-positive handling: exceptions, allow lists, and a reproducible log.
