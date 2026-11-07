/*
 * su_hook.c - Hook framework + execve interception.
 *
 * Phase 2 scope: a small, reusable hook-registration framework built on
 * kprobes, plus one concrete hook: intercepting attempts to execute the
 * su client binary and deciding (via su_allowlist.c) whether to elevate
 * credentials for the calling process.
 *
 * Path-hiding hooks (open/stat/access), described in the Phase 1 design
 * doc, will register through this same su_register_hook() API in a
 * later phase - nothing here is execve-specific at the framework level.
 *
 * NOTE on symbol resolution: as of recent GKI kernels, kallsyms_lookup_name()
 * is no longer exported to modules. The common, publicly documented
 * workaround is to register a throwaway kprobe on "kallsyms_lookup_name"
 * itself to read its resolved address out of the probe, then call through
 * that function pointer to resolve everything else. This is a generic
 * Linux kernel technique, not specific to any particular root project.
 */

#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/string.h>
#include "su_common.h"

/* Path of the su client binary. TODO(Phase 3): make this configurable at
 * module load time (module param) instead of a compile-time constant, so
 * the manager app / build system can pick a non-obvious path per build. */
#define SU_CLIENT_PATH "/system/bin/su_client"

#define SU_MAX_HOOKS 8

struct su_hook_entry {
	const char *symbol;
	struct kprobe kp;
	bool installed;
};

static struct su_hook_entry hooks[SU_MAX_HOOKS];
static int hook_count;

typedef unsigned long (*kallsyms_lookup_name_t)(const char *name);
static kallsyms_lookup_name_t su_kallsyms_lookup_name;

/* ---- bootstrap: resolve kallsyms_lookup_name via a throwaway kprobe ---- */

static int su_resolve_kallsyms(void)
{
	struct kprobe kp = {
		.symbol_name = "kallsyms_lookup_name",
	};
	int ret = register_kprobe(&kp);

	if (ret < 0) {
		su_err("failed to resolve kallsyms_lookup_name (ret=%d)\n", ret);
		return ret;
	}

	su_kallsyms_lookup_name = (kallsyms_lookup_name_t)kp.addr;
	unregister_kprobe(&kp);

	if (!su_kallsyms_lookup_name) {
		su_err("kallsyms_lookup_name resolved to NULL\n");
		return -ENOENT;
	}
	return 0;
}

/* ---- generic hook registration ---- */

static int su_register_hook(const char *symbol, kprobe_pre_handler_t pre)
{
	struct su_hook_entry *h;
	int ret;

	if (hook_count >= SU_MAX_HOOKS)
		return -ENOSPC;

	h = &hooks[hook_count];
	memset(&h->kp, 0, sizeof(h->kp));
	h->symbol = symbol;
	h->kp.symbol_name = symbol;
	h->kp.pre_handler = pre;

	ret = register_kprobe(&h->kp);
	if (ret < 0) {
		su_err("failed to hook '%s' (ret=%d)\n", symbol, ret);
		return ret;
	}

	h->installed = true;
	hook_count++;
	su_log("hooked '%s' at %px\n", symbol, h->kp.addr);
	return 0;
}

static void su_unregister_all_hooks(void)
{
	int i;

	for (i = 0; i < hook_count; i++) {
		if (hooks[i].installed) {
			unregister_kprobe(&hooks[i].kp);
			hooks[i].installed = false;
		}
	}
	hook_count = 0;
}

/* ---- credential elevation ---- */

static void su_elevate_current_task(void)
{
	struct cred *new_cred = prepare_kernel_cred(NULL);

	if (!new_cred) {
		su_err("prepare_kernel_cred failed\n");
		return;
	}

	new_cred->uid.val = 0;
	new_cred->gid.val = 0;
	new_cred->euid.val = 0;
	new_cred->egid.val = 0;
	new_cred->suid.val = 0;
	new_cred->sgid.val = 0;
	new_cred->fsuid.val = 0;
	new_cred->fsgid.val = 0;

	/* TODO(Phase 5): also raise the capability sets explicitly rather
	 * than relying only on uid 0, and apply the correct SELinux domain
	 * transition instead of leaving the calling process's context
	 * untouched - needed for a clean "root but still policy-constrained"
	 * model rather than a blunt permissive-everything grant. */

	commit_creds(new_cred);
}

/* ---- the execve pre-handler ---- */

/*
 * Deliberately written against the *symbol name only* for now; the exact
 * register holding the filename argument is architecture/ABI specific
 * and is filled in once we pick the exact hook point (see TODO below).
 * Left as a stub for the next working session so this compiles as a
 * skeleton without claiming a working extraction we haven't validated
 * yet.
 */
static int su_execve_pre_handler(struct kprobe *kp, struct pt_regs *regs)
{
	uid_t uid = current_uid().val;
	u32 flags;

	/* TODO(Phase 2 continued): extract the exec filename from regs per
	 * the target hook point's calling convention (arm64 syscall wrapper
	 * vs do_execveat_common's struct filename * argument differ) and
	 * only proceed past this point if it matches SU_CLIENT_PATH. For
	 * now this stub treats every hooked call as a su-client call so the
	 * allowlist/elevate/notify logic below can be exercised and tested
	 * independently of the filename-extraction work. */

	if (su_allowlist_check(uid, &flags)) {
		su_log("uid=%u has grant (flags=0x%x), elevating\n", uid, flags);
		su_elevate_current_task();
	} else {
		su_log("uid=%u not in allowlist, denying + notifying daemon\n", uid);
		su_notify_unknown_uid(uid);
		/* Default-deny: do not touch creds, caller proceeds unprivileged. */
	}

	return 0;
}

/* ---- public entry points ---- */

int su_hooks_install(void)
{
	int ret = su_resolve_kallsyms();

	if (ret < 0)
		return ret;

	/* TODO(Phase 2 continued): pick the final symbol to hook. Candidates
	 * to evaluate on a GKI 2.0 test kernel:
	 *   - "__arm64_sys_execve" / "__arm64_sys_execveat" (syscall wrapper,
	 *     arm64 GKI naming)
	 *   - "do_execveat_common" (shared internal implementation, one hook
	 *     covers execve + execveat + fexecve)
	 * do_execveat_common is probably the better target since it covers
	 * all three entry points in one hook, but needs confirming its
	 * signature/visibility across the android12-5.10 .. android15-6.6
	 * KMI versions we're targeting. */
	return su_register_hook("do_execveat_common", su_execve_pre_handler);
}

void su_hooks_uninstall(void)
{
	su_unregister_all_hooks();
}
