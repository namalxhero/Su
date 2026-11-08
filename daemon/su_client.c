/*
 * su_client.c - the actual userspace binary behind "su" requests.
 *
 * Deployed at /system/bin/su_client (kernel/su_hook.c's SU_CLIENT_PATH,
 * kernel/su_hide.c's hidden-path list - both hardcode this exact path,
 * so this binary MUST be installed there, not just anywhere on PATH).
 *
 * This was referenced by path in kernel/ since Phase 2 but never
 * actually written - the whole su mechanism assumed a binary existed
 * here without one existing. Found and fixed while building manager-app
 * features that needed a real root shell (Modules tab).
 *
 * WHY THIS FILE IS SO SHORT: it doesn't need any privilege-elevation
 * code of its own. su_hook.c's kprobe on do_execveat_common() detects
 * this exact path being exec'd, checks the calling UID against the
 * kernel allowlist, and - if granted - queues the credential elevation
 * via task_work BEFORE this binary's own first instruction runs (see
 * kernel/su_hook.c's su_queue_elevate_current_task()). By the time
 * main() below executes, the calling process already has root creds if
 * it was going to get them at all; this binary's only job is to then
 * behave like a conventional Unix `su`: run a shell, or run `-c
 * <command>`, and exec into it. If the caller wasn't granted, none of
 * that happens and this just runs as whatever uid+command it would
 * have anyway - no special-casing needed on this side either way.
 *
 * Supports the two invocation shapes the rest of this project (and any
 * generic ProcessBuilder("su_client", ...) caller) needs:
 *   su_client                  -> exec an interactive shell
 *   su_client -c "<command>"   -> exec `sh -c "<command>"`
 *
 * NOT implemented (deliberately, out of scope for this pass): -s
 * <shell>, -lp (login shell / preserve env), --mount-master, or any of
 * the other flags real `su` implementations (toolbox, Magisk's,
 * KernelSU's) accept. Added if/when something actually needs them.
 */

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define DEFAULT_SHELL "/system/bin/sh"

int main(int argc, char **argv)
{
	if (argc >= 3 && strcmp(argv[1], "-c") == 0) {
		/*
		 * Concatenate argv[2..] as sh -c expects a single command
		 * string - mirrors how a real su/sh handles multiple
		 * trailing args to -c (they get space-joined and re-parsed
		 * as one shell command line, not passed as $1 $2 ... to
		 * the command). Most callers (including this project's own
		 * ModuleRepository.kt) pass exactly one already-quoted
		 * string in argv[2], so the common case is just that one
		 * arg.
		 */
		char cmd[4096] = {0};
		size_t used = 0;
		int i;

		for (i = 2; i < argc; i++) {
			int n = snprintf(cmd + used, sizeof(cmd) - used, "%s%s",
					  i > 2 ? " " : "", argv[i]);
			if (n < 0 || (size_t)n >= sizeof(cmd) - used) {
				fprintf(stderr, "su_client: command too long\n");
				return 1;
			}
			used += (size_t)n;
		}

		execl(DEFAULT_SHELL, "sh", "-c", cmd, (char *)NULL);
		perror("su_client: execl(sh -c) failed");
		return 1;
	}

	if (argc != 1) {
		fprintf(stderr, "usage: su_client [-c \"<command>\"]\n");
		return 2;
	}

	execl(DEFAULT_SHELL, "sh", (char *)NULL);
	perror("su_client: execl(sh) failed");
	return 1;
}
