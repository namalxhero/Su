/*
 * sud.c - entry point. Invoked by the kernel's call_usermodehelper()
 * (see kernel/su_boot_trigger.c) as:
 *     sud post-fs-data
 *     sud service
 *
 * boot-completed is deliberately NOT a stage this binary handles - see
 * docs/PHASE4_DESIGN.md "How the trigger works", step 5: the manager
 * app's BootReceiver (Phase 3) does that once Android userspace is up.
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "sud.h"

/*
 * Real Android systems overlay more than just /system (/vendor,
 * /product, /system_ext depending on partition layout), but which of
 * those exist varies by device/Android version - hardcoding the full
 * list here would be exactly the kind of device-specific assumption
 * this project is trying to avoid (goal #5). For this skeleton, only
 * /system is wired up; extending the list is a one-line addition to
 * this array once there's a real device to validate "which partitions
 * actually exist" detection against (stat() each candidate before
 * adding it, don't assume).
 */
static const char *overlay_targets[] = {
	"/system",
};
#define NUM_OVERLAY_TARGETS (sizeof(overlay_targets) / sizeof(overlay_targets[0]))

int sud_run_module_script(const char *module_dir, const char *script_name)
{
	char script_path[SUD_MAX_PATH];
	pid_t pid;
	int status;
	int waited = 0;

	snprintf(script_path, sizeof(script_path), "%s/%s", module_dir, script_name);
	if (access(script_path, X_OK) != 0)
		return 0; /* no script, or not executable - not an error */

	pid = fork();
	if (pid < 0) {
		perror("sud: fork");
		return -1;
	}
	if (pid == 0) {
		/* child: run the script with a plain shell, same as Magisk */
		execl("/system/bin/sh", "sh", script_path, (char *)NULL);
		_exit(127); /* execl only returns on failure */
	}

	/* parent: poll with WNOHANG so we can enforce SUD_SCRIPT_TIMEOUT_SEC
	 * instead of blocking boot indefinitely on a hung module script. */
	while (waited < SUD_SCRIPT_TIMEOUT_SEC) {
		pid_t r = waitpid(pid, &status, WNOHANG);
		if (r == pid)
			return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
		sleep(1);
		waited++;
	}

	fprintf(stderr, "sud: %s timed out after %ds, killing\n", script_path,
		SUD_SCRIPT_TIMEOUT_SEC);
	kill(pid, SIGKILL);
	waitpid(pid, &status, 0); /* reap it so it doesn't linger as a zombie */
	return -1;
}

static int run_post_fs_data(void)
{
	struct sud_module modules[SUD_MAX_MODULES];
	int count = sud_scan_modules(modules, SUD_MAX_MODULES);
	unsigned int i;
	int m;

	if (count < 0) {
		fprintf(stderr, "sud: failed to open %s, nothing to do\n", SUD_MODULES_DIR);
		return 0; /* not fatal - a device with no modules dir just has no modules */
	}

	fprintf(stderr, "sud: post-fs-data, %d enabled module(s)\n", count);

	/*
	 * Ordering (see design doc): each module's post-fs-data.sh runs
	 * BEFORE any overlay is mounted (a script may stage files the
	 * overlay should then expose), then overlays are mounted once, in
	 * one pass per target mount point across all modules - mounting
	 * per-module per-target would mean module B's overlay sits ON TOP
	 * of module A's already-mounted overlay instead of both being
	 * proper lowerdir layers of a single mount.
	 */
	for (m = 0; m < count; m++) {
		char module_dir[SUD_MAX_PATH];
		snprintf(module_dir, sizeof(module_dir), "%s/%s", SUD_MODULES_DIR,
			 modules[m].dir_name);

		if (modules[m].has_post_fs_data_sh)
			sud_run_module_script(module_dir, "post-fs-data.sh");
	}

	for (i = 0; i < NUM_OVERLAY_TARGETS; i++)
		sud_overlay_apply(modules, count, overlay_targets[i]);

	return 0;
}

static int run_service(void)
{
	struct sud_module modules[SUD_MAX_MODULES];
	int count = sud_scan_modules(modules, SUD_MAX_MODULES);
	int m;

	if (count < 0)
		return 0;

	fprintf(stderr, "sud: service, %d enabled module(s)\n", count);

	for (m = 0; m < count; m++) {
		char module_dir[SUD_MAX_PATH];
		snprintf(module_dir, sizeof(module_dir), "%s/%s", SUD_MODULES_DIR,
			 modules[m].dir_name);

		if (modules[m].has_service_sh)
			sud_run_module_script(module_dir, "service.sh");
	}

	return 0;
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		fprintf(stderr, "usage: %s <post-fs-data|service>\n", argv[0]);
		return 2;
	}

	if (strcmp(argv[1], "post-fs-data") == 0)
		return run_post_fs_data();
	if (strcmp(argv[1], "service") == 0)
		return run_service();

	fprintf(stderr, "sud: unknown stage '%s'\n", argv[1]);
	return 2;
}
