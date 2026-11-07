# Phase 1 — Research / Design: su Mechanism Architecture

Status: **decided**, implemented as the Phase 2 kernel module skeleton.

## Scope decision

- **Kernel support target:** GKI 2.0 only (Linux 5.10+, Android 12+) for
  the initial release. Non-GKI / older kernels may be revisited later as
  a separate build variant; not in scope now.
- **Distribution model:** this project ships kernel module *source* plus
  an integration guide. End users / kernel builders integrate it into
  their own kernel source and build + flash it themselves. The project's
  own build pipeline (GitHub Actions) builds and ships the **manager
  app** (APK) and verifies the kernel module source compiles against a
  reference GKI tree — it does not produce or distribute prebuilt,
  flashable kernels for specific devices.

## su mechanism

Three options were considered:

| Option | Description | Verdict |
|---|---|---|
| A. Kernel source patch | Modify kernel source directly, rebuild whole kernel | Rejected as primary — conflicts with the device-agnostic goal (would require a full custom kernel build per device) |
| B. Out-of-tree LKM + kprobe/ftrace hook | Loadable module, hooks execve via kprobes, no kernel source changes needed beyond build-system integration | **Chosen** |
| C. Userspace daemon (ptrace-based) | No kernel component at all | Rejected — doesn't meet the "kernel-level" requirement, weaker against detection, higher overhead |

**Hook point:** execve path, via kprobe (framework also usable for
future path-hiding hooks — see below). Exact symbol (`do_execveat_common`
vs the arm64 syscall wrapper) still to be validated empirically in
Phase 2 continuation.

## Allowlist model: Hybrid

- Kernel keeps a small in-memory UID cache (`su_allowlist.c`), checked
  synchronously on the hot path — no userspace round-trip per `execve`.
- The manager app daemon is the source of truth for persistent policy
  and pushes grants/revokes into the kernel cache via ioctl
  (`SU_IOC_UID_GRANT` / `SU_IOC_UID_REVOKE`), including a push at boot.
- Rationale: pure userspace-round-trip-per-call is too slow and breaks
  during early boot stages before the daemon is up; pure kernel-only
  storage makes the manager app UI harder to keep in sync.

## Unknown-UID ("first request") flow

- Default-deny immediately when an uncached UID calls the su client —
  no blocking/waiting in the kernel hook.
- The event is recorded as a single pending-request slot and the daemon
  is woken via a blocking `read()` / wait queue on `/dev/su_ctl`.
- Daemon shows an Allow/Deny prompt; on Allow it pushes a grant via
  ioctl. The requesting app must retry `su` manually afterward (no
  automatic retry from the kernel side).

## Planned, not yet designed in detail

- **Root hiding / deny-list** (mount-namespace isolation + path-hiding
  for `open`/`stat`/`access` on the su client path), to keep apps like
  banking apps that do simple path checks from detecting root. Will
  register through the same `su_register_hook()` framework as the
  execve hook. Explicitly **not** a solution to hardware-backed
  attestation (Play Integrity strong integrity) — that's a separate,
  much harder problem, intentionally out of scope for now.
- Magisk-module-compatible OverlayFS mount system (module.prop,
  post-fs-data/service/boot-completed stages) — Phase 4.
