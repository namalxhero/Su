/*
 * su_boot_trigger.c - one-shot "launch sud at post-fs-data/service" boot
 * hook (Phase 4). See docs/PHASE4_DESIGN.md "How the trigger works".
 *
 * Does NOT install its own kprobe. su_hook.c's existing
 * do_execveat_common hook (installed once in su_hooks_install(), Phase
 * 2) already fires on every execve in the system; su_hook.c's
 * pre_handler calls su_boot_trigger_check() with the exec path after
 * its own su-client check, so this piggybacks on that one kprobe
 * instead of registering a second one on the same function.
 *
 * TRIGGER POINT IS A PLACEHOLDER (see docs/PHASE4_DESIGN.md "Known
 * gaps"): SU_BOOT_TRIGGER_PATH below needs to be a binary that
 *   - /init (or something it starts very early) execve's exactly once
 *     per boot,
 *   - runs late enough that /data is mounted + decrypted (so
 *     /data/adb/modules is actually readable) - fscrypt/vold timing
 *     varies by Android version, which needs validating on a real
 *     device, not guessed from here,
 *   - runs early enough that overlaying /system still matters, i.e.
 *     before zygote/app processes start reading it.
 * "/system/bin/vold" below is a placeholder guess, not a validated
 * choice - swap it once there's a device to test boot-stage timing on.
 */

#include <linux/kmod.h>
#include <linux/string.h>
#include "include/su_common.h"

#define SUD_BINARY_PATH "/data/adb/sud"
#define SU_BOOT_TRIGGER_PATH "/system/bin/vold" /* PLACEHOLDER - see file comment */

static bool su_boot_fired;

static void su_launch_sud_stage(const char *stage)
{
	char *argv[] = { SUD_BINARY_PATH, (char *)stage, NULL };
	char *envp[] = { "PATH=/sbin:/system/sbin:/system/bin:/system/xbin", NULL };
	int ret;

	/*
	 * UMH_WAIT_PROC: blocks the calling (kernel) context until sud
	 * exits. Deliberate for "post-fs-data" - module overlays must be
	 * mounted before boot continues, or apps can start reading the
	 * un-overlaid partition (see design doc step 2). "service" doesn't
	 * strictly need to block, but is launched the same way for now to
	 * keep this function single-purpose; splitting that out is a later
	 * optimisation, not a correctness issue.
	 *
	 * No extra timeout guard here beyond whatever
	 * call_usermodehelper()/UMH_WAIT_PROC already enforces internally -
	 * a hung/missing sud stalls boot for that long. Tracked as a Known
	 * gap in the design doc, not fixed in this skeleton.
	 */
	ret = call_usermodehelper(argv[0], argv, envp, UMH_WAIT_PROC);
	if (ret != 0)
		su_warn("sud %s failed or sud missing (ret=%d) - modules not applied this boot\n",
			stage, ret);
	else
		su_log("sud %s completed\n", stage);
}

void su_boot_trigger_check(const char *exec_path)
{
	if (su_boot_fired || !exec_path)
		return;

	if (strcmp(exec_path, SU_BOOT_TRIGGER_PATH) != 0)
		return;

	/* Set before launching, not after: a slow or hung sud must not
	 * cause su_hook.c to call back in here again for a later execve
	 * that happens to match while the first call is still running (it
	 * can't in practice since UMH_WAIT_PROC blocks this whole context,
	 * but setting the flag first costs nothing and removes the
	 * ordering assumption). */
	su_boot_fired = true;

	su_launch_sud_stage("post-fs-data");
	su_launch_sud_stage("service");
}
