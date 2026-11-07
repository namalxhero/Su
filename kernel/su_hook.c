/*
 * su_hook.c - Hook framework + execve interception.
 *
 * Hook point: do_execveat_common(), via kprobe (register_kprobe() matched
 * by symbol name - no kernel source patch needed on trees where the
 * symbol is visible to kallsyms).
 *
 * FIX (Phase 2 review, #1 - critical): the previous version called
 * commit_creds(prepare_kernel_cred(...)) directly inside the kprobe
 * pre_handler. Kprobe handlers run with preemption disabled, and
 * prepare_kernel_cred() allocates from cred_jar with GFP_KERNEL, which
 * is allowed to sleep. Sleeping with preemption disabled is a real
 * "scheduling while atomic" bug - it mostly "works" under light memory
 * pressure and then warns/panics under load.
 *
 * Fix: the pre_handler only queues a su_elevate_work via task_work_add()
 * (GFP_ATOMIC allocation - safe in kprobe context). task_work runs in
 * full process (sleepable) context on the same task right before it
 * returns to userspace - still before the exec'd image (su_client)
 * starts running, so su_client sees root creds from its first
 * instruction, but the actual prepare_kernel_cred()/commit_creds() call
 * happens somewhere it's legal to sleep.
 *
 * FIX (Phase 2 review, #2 - critical): do_execveat_common()'s argument
 * layout is not the same across kernel trees. Most mainline-derived
 * trees (~4.x+, including GKI 2.0) pass an explicit `int fd` as the
 * first argument, so `filename` is argument #2 (regs->regs[1] on
 * arm64). Older trees (e.g. Samsung's 3.18-based Exynos kernels) have
 * no `fd` parameter, so `filename` is argument #1 (regs->regs[0]). The
 * previous version always read regs->regs[1], which on a no-fd kernel
 * reads the wrong register and casts garbage to struct filename* -
 * silent memory corruption, not a build failure. kernel/Kbuild now
 * greps the target tree's fs/exec.c and defines SU_EXECVE_HAS_FD_ARG
 * accordingly; see su_is_su_client_call() below.
 *
 * CAVEAT: still not validated against a real build (no reference kernel
 * tree in this repo to compile against - see README "Integration").
 * Treat the task_work-based deferral and the fd-arg detection as
 * reviewed-and-believed-correct, not compile-tested.
 */

#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/sched/task.h>
#include <linux/cred.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/fs.h>
#include <linux/task_work.h>
#include <linux/version.h>
#include <linux/err.h>
#include "su_common.h"

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

/* ---- deferred elevation (fix #1) ---- */

struct su_elevate_work {
	struct callback_head twork; /* must be first: task_work_add() takes
				      * the address of this field. */
	pid_t pid; /* for logging only */
};

static void su_do_elevate(struct callback_head *twork)
{
	struct su_elevate_work *work = container_of(twork, struct su_elevate_work, twork);
	struct cred *new_cred = prepare_kernel_cred(NULL);

	if (!new_cred) {
		su_err("prepare_kernel_cred failed (deferred elevation, pid=%d)\n", work->pid);
		kfree(work);
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

	commit_creds(new_cred);
	su_log("elevation applied in process context (pid=%d)\n", work->pid);
	kfree(work);
}

/* Called from kprobe context (preemption disabled) - must not sleep or
 * take non-raw spinlocks. GFP_ATOMIC does not sleep. */
static void su_queue_elevate_current_task(void)
{
	struct su_elevate_work *work = kmalloc(sizeof(*work), GFP_ATOMIC);

	if (!work) {
		su_err("failed to allocate elevation work (pid=%d)\n", current->pid);
		return;
	}

	work->pid = current->pid;
	init_task_work(&work->twork, su_do_elevate);

#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 16, 0)
	if (task_work_add(current, &work->twork, TWA_RESUME)) {
#else
	if (task_work_add(current, &work->twork, true)) {
#endif
		su_err("task_work_add failed, dropping elevation for pid=%d\n", current->pid);
		kfree(work);
	}
}

/* ---- symbol resolution ---- */

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

/*
 * FIX (Phase 2 review, #4): previously resolved and never used anywhere
 * (dead code left over from an incomplete integration). Now exposed so
 * future hook backends (syscall-table patch, manual source hook for
 * kernels where kprobes on static symbols aren't visible) can resolve
 * symbols the same way instead of each reimplementing the kprobe trick.
 */
unsigned long su_lookup_symbol(const char *name)
{
	if (!su_kallsyms_lookup_name)
		return 0;
	return su_kallsyms_lookup_name(name);
}

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

static bool su_is_su_client_call(struct pt_regs *regs)
{
#ifdef CONFIG_ARM64
	struct filename *fname;

#ifdef SU_EXECVE_HAS_FD_ARG
	fname = (struct filename *)regs->regs[1];
#else
	fname = (struct filename *)regs->regs[0];
#endif

	if (!fname || IS_ERR(fname) || !fname->name)
		return false;

	return strcmp(fname->name, SU_CLIENT_PATH) == 0;
#else
#error "su_hook.c's register-based argument extraction is arm64-only for now."
#endif
}

static int su_execve_pre_handler(struct kprobe *kp, struct pt_regs *regs)
{
	uid_t uid;
	u32 flags;

	if (!su_is_su_client_call(regs))
		return 0;

	uid = current_uid().val;

	if (su_allowlist_check(uid, &flags)) {
		su_log("uid=%u has grant (flags=0x%x), queuing elevation\n", uid, flags);
		su_queue_elevate_current_task();
	} else {
		su_log("uid=%u not in allowlist, denying + notifying daemon\n", uid);
		su_notify_unknown_uid(uid);
	}

	return 0;
}

int su_hooks_install(void)
{
	int ret = su_resolve_kallsyms();

	if (ret < 0)
		return ret;

	/*
	 * do_execveat_common is the native (64-bit) execve path. On most
	 * trees the compat (32-bit app) syscall entry funnels into this
	 * same C function, so one hook should cover both - but this is not
	 * validated against every tree's compat path (fix #5 flagged this
	 * as a known gap). If 32-bit su_client calls aren't being caught on
	 * a given device, the compat execve path is the first place to
	 * check.
	 */
	return su_register_hook("do_execveat_common", su_execve_pre_handler);
}

void su_hooks_uninstall(void)
{
	su_unregister_all_hooks();
}
