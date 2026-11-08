# Phase 6 — Build Pipeline

Status: **kernel module CI added; manager app CI already existed
(`.github/workflows/manager-build.yml`, added during "Phase 3
continued" - see that commit's history). This phase closes the
remaining half: the kernel module had no CI at all.**

## Why this matters more than it sounds like it should

Every `kernel/*.c` file in this tree carries some version of the same
caveat: *"reviewed, not compiled against a real kernel tree."* That was
true because no kernel source was available in the environment these
files were written in. `.github/workflows/kernel-build.yml` is the
first thing in this project that actually attempts to compile `kernel/`
for real, rather than the brace/paren-balance sanity checks used as a
substitute up to this point.

## What it does - and what it deliberately does NOT do

Builds `kernel/` as an out-of-tree module (`make M=...`) against two
**plain upstream `kernel.org` (vanilla mainline) tags** - not any
Android or device-specific kernel fork:

- **vanilla-v3.18** - `torvalds/linux` @ `v3.18`, generic in-tree
  `arch/arm64/configs/defconfig`. No `fd` argument.
- **vanilla-v6.6** - `torvalds/linux` @ `v6.6`, same generic
  `defconfig`. Has the `int fd` argument.

`do_execveat_common()` is itself upstream (introduced for the
`execveat()` syscall, ~v3.19) - not an Android or vendor addition - so
vanilla tags on either side of that commit exercise both
`SU_EXECVE_HAS_FD_ARG` branches (see Phase 2) without referencing any
real device's kernel at all. A green build on only one of these would
not prove both `su_extract_exec_path()` branches actually compile -
that's the reason for two tags instead of one.

**This matters beyond just picking a cleaner test target.** An earlier
version of this CI used an Android GKI tree and a Samsung Exynos device
fork, which wrongly implied this project does device-specific kernel
building as part of its own pipeline. It doesn't: building the real,
flashable kernel for a specific phone - device defconfig, vendor
patches, `kernel/` integrated into that tree per `kernel/Kbuild`'s and
`../README.md`'s "Integration" notes - is, and has always been, the end
user's own job. This CI only ever answers "does the module's source
compile against generic upstream kernel internals," nothing more.

Each matrix entry: checks out the target tree, installs an
`aarch64-linux-gnu-` cross toolchain, runs the tree's own `defconfig` +
`modules_prepare` (needed to generate the headers/`Module.symvers` an
out-of-tree module builds against), then `make M=kernel modules`.
A passing build is checked for `su_core.ko` actually existing (not just
`make` returning 0) and for Kbuild's `$(info ...)` line confirming the
*expected* `SU_EXECVE_HAS_FD_ARG` branch was taken for that tree - a
silently-wrong detection on one side would otherwise still "pass".
Compiler warnings are collected into the job summary but don't fail the
build (only an actual compile error does).

## Known gaps

- **Not yet run.** Same standing caveat as everywhere else in this
  kernel tree - written and reviewed, not proven. First real run (on
  push, or via `workflow_dispatch`) is the actual validation step.
- Produces `su_core.ko` as a build artifact for inspection only - this
  is **not** a flashable/installable output on its own (no boot.img
  integration, no signing, no device-specific packaging). Turning a
  passing CI build into something a device can actually load is a
  later step, not covered here.
- Only validates compilation, not runtime behaviour - a clean build
  says nothing about whether `su_hooks_install()` successfully
  registers its kprobes on either tree, whether `su_boot_trigger.c`'s
  placeholder trigger path is right, or any of the other "needs a real
  device" caveats scattered through Phase 2-5's design docs.
- Two trees, not a broader matrix. A device on a kernel version/vendor
  fork materially different from both (e.g. a non-Samsung Exynos
  derivative, or a kernel between 3.18 and 5.10) isn't represented here
  and could still fail to compile even with both these jobs green.
