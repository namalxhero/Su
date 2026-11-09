# Phase 8 — Deep-kernel rework: tracepoint migration + CONFIG_SU_CORE

Status: **implemented, not yet run through CI/a real device.**

## 1. `sched_process_exec` tracepoint replaces the `do_execveat_common` kprobe

Removed entirely:
- `su_hook.c`'s kprobe on `do_execveat_common`
- `kernel/Kbuild`'s `SU_EXECVE_HAS_FD_ARG` build-time detection
  (`kernel/scripts/detect-execve-fd-arg.sh`, now dead, deleted)
- `su_extract_exec_path()`'s register-layout guessing (`regs->regs[0]`
  vs `regs->regs[1]` depending on whether the target tree's
  `do_execveat_common` took an `int fd` first argument)

Replaced with `register_trace_sched_process_exec()` (declared via
`<trace/events/sched.h>`, `EXPORT_SYMBOL_GPL` - this module is already
GPL-licensed). The exec path now comes from
`d_path(&bprm->file->f_path, ...)` - a real, resolved, canonical path,
not a raw argument register.

**Why this was worth doing:** the fd-arg detection was solving a
problem that doesn't exist for a tracepoint. `do_execveat_common()`'s
signature varies across kernel trees because it's an internal function
with no stability guarantee; `sched_process_exec`'s `TP_PROTO(struct
task_struct *p, pid_t old_pid, struct linux_binprm *bprm)` is a stable,
versioned tracepoint ABI that hasn't changed shape across any kernel
version this project targets. One whole class of "did we read the
right register" risk - the thing that was Phase 2's #2 critical bug in
the first place - is now structurally impossible, not just more
carefully guarded against.

**What did NOT change:** tracepoint probes still run with
`rcu_read_lock_sched_notrace()` held - sleeping is still disallowed
inside the probe itself. Phase 2's `task_work_add()` deferral for
`prepare_kernel_cred()`/`commit_creds()` is unchanged and still
required; only the registration mechanism moved, not the atomicity
constraint. `su_hook.c`'s own comments were updated to say this
precisely (the previous wording was kprobe-specific - "preemption
disabled" - which isn't quite the right description of why a
tracepoint probe can't sleep either, even though the practical
restriction is the same).

**Compat (32-bit) coverage**, previously an unvalidated assumption (fix
#5 in Phase 2's review), should now actually hold without separate
handling: `sched_process_exec` fires from the shared
`begin_new_exec()` path both native and compat syscall entries funnel
into. Still worth confirming on a real 32-bit-capable device rather
than treating this as settled just because the mechanism is cleaner.

## 2. `CONFIG_SU_CORE` Kconfig tristate

New `kernel/Kconfig`, sourced from a target tree's own Kconfig once
`kernel/` is integrated (see `../README.md`). `kernel/Kbuild` changed
from `obj-m += su_core.o` (always loadable) to `obj-$(CONFIG_SU_CORE)
+= su_core.o` - the same source tree now produces either a loadable
module (`CONFIG_SU_CORE=m`, the default) or a built-in (`=y`) depending
entirely on the target kernel's own `.config`/defconfig, with no manual
Kbuild editing required either way.

`=y` (built-in) is what Phase 7's `tools/boot_patch/` needs something
to actually patch into a boot image - this Kconfig entry is what makes
choosing that mode a one-line defconfig change instead of hand-editing
`Kbuild` every time.

**CI note:** `.github/workflows/kernel-build.yml` now passes
`CONFIG_SU_CORE=m` directly on the `make` command line, since neither
target tree in its matrix (plain upstream `torvalds/linux` tags) has
any knowledge of this project's own Kconfig symbol - `kernel/Kconfig`
only gets sourced once a real integrator places `kernel/` inside their
own tree and wires it in, which CI deliberately doesn't simulate (see
`docs/PHASE6_DESIGN.md`'s "doesn't imply device-specific kernel
building" point - that still holds).

## Known gaps / explicitly deferred

- **The `su_lookup_symbol()`/kallsyms trick is still used by
  `su_mount_isolate.c`** (for `create_new_namespaces`,
  `switch_task_namespaces`, `path_umount` - all genuinely unexported).
  A built-in (`CONFIG_SU_CORE=y`) module could in principle reference
  these directly via `extern` declarations instead of runtime symbol
  resolution, removing that entire class of risk the same way the
  tracepoint migration removed the execve one - but doing that only for
  the `=y` case while still needing the kallsyms fallback for `=m` adds
  real conditional-compilation complexity, and was deliberately NOT
  bundled into this phase. Tracked as a separate follow-up, not done
  here.
- **`su_hide.c`'s `vfs_statx`/`getdents64` kretprobes are unrelated to
  this migration and unchanged** - no stable tracepoint exists for
  "a path was stat'd" or "a directory was listed" the way one exists
  for exec, so those stay kprobe-based.
- **Not run through CI yet.** The kprobe-based version this replaces
  WAS validated end-to-end (see `kernel-build.yml`'s own header comment
  for the real, reproduced-locally fixes that took) - this rewrite has
  not been through that same matrix. Treat it as reviewed, not proven,
  until it has.
