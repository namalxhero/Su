/*
 * su_hook.c - execve interception.
 *
 * DEEP-KERNEL REWORK: this file used to hook do_execveat_common() via
 * kprobe, with kernel/Kbuild grepping the target tree's fs/exec.c at
 * build time to figure out whether that function takes an `int fd`
 * first argument (most 4.x+ trees) or not (e.g. Samsung's 3.18-based
 * Exynos kernels) - see git history / docs/PHASE2 for that approach and
 * why it existed.
 *
 * That entire problem class is now GONE: this hooks the kernel's own
 * `sched_process_exec` tracepoint instead (register_trace_sched_process_exec(),
 * declared via <trace/events/sched.h>, EXPORT_SYMBOL_GPL'd - our module
 * is GPL-licensed, so this links fine). It is:
 *   - stable API: TP_PROTO(struct task_struct *p, pid_t old_pid, struct
 *     linux_binprm *bprm) has not changed shape across the kernel
 *     versions this project targets (the tracepoint itself predates
 *     3.18 by a wide margin - process tracepoints are old infrastructure).
 *   - not register-layout-guessing: the exec path comes from
 *     d_path(&bprm->file->f_path, ...) - a real, canonical, resolved
 *     path, not a raw pointer pulled out of an argument register whose
 *     position depends on a function signature that varies by tree.
 *   - still available on every target kernel version in this project's
 *     CI matrix (v3.18 and v6.6 both have it - CONFIG_TRACEPOINTS is
 *     virtually always enabled on Android kernels regardless, since
 *     ftrace/systrace depend on it).
 *
 * WHAT DID NOT CHANGE: tracepoint probes run with
 * rcu_read_lock_sched_notrace() held around every registered callback -
 * sleeping is still not allowed in the probe itself, same restriction a
 * kprobe pre_handler has (just a different kernel mechanism enforcing
 * it, not "preemption disabled" specifically - corrected here since the
 * old comment's reasoning was kprobe-specific wording that no longer
 * applies verbatim). Phase 2's task_work_add() deferral pattern for
 * credential elevation is therefore still required and unchanged in
 * su_do_elevate()/su_queue_elevate_current_task() below.
 *
 * su_lookup_symbol()/su_resolve_kallsyms() stay in this file - they are
 * no longer needed for OUR OWN hook (the tracepoint needs no kallsyms
 * trick at all), but su_mount_isolate.c still depends on
 * su_lookup_symbol() to resolve create_new_namespaces() /
 * switch_task_namespaces() / path_umount(), which remain genuinely
 * unexported. Moving this elsewhere was considered and skipped - it's
 * a small, self-contained piece of code and su_mount_isolate.c already
 * assumes su_hooks_install() (which still calls su_resolve_kallsyms()
 * first) runs before it's needed.
 *
 * CAVEAT: still not validated against a real build - same standing
 * caveat as the rest of this kernel tree. The register_kprobe-based
 * version this replaces WAS validated (see
 * .github/workflows/kernel-build.yml's header comment for the real,
 * reproduced-locally fixes that took to get that version compiling on
 * v3.18 and v6.6) - this tracepoint-based rewrite has not yet been run
 * through that same CI matrix. Treat it as reviewed, not proven, until
 * it has.
 */

#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/version.h>
/* linux/sched/task.h (and the rest of the sched.h split) only exists
 * from ~v4.11 onward; everything this file needs from it existed
 * directly in linux/sched.h on older trees like Samsung's 3.18-based
 * Exynos kernels. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
#include <linux/sched/task.h>
#endif
#include <linux/cred.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/fs.h>
#include <linux/binfmts.h>
#include <linux/dcache.h>
#include <linux/path.h>
#include <linux/task_work.h>
#include <linux/err.h>
#include <trace/events/sched.h>
#include "su_common.h"

#define SU_CLIENT_PATH "/system/bin/su_client"
#define SU_EXEC_PATH_BUF 512

/* su_lookup_symbol() is still used by this file's own
 * su_resolve_kallsyms() plumbing AND by su_mount_isolate.c - kept here,
 * see file header "su_lookup_symbol()/su_resolve_kallsyms() stay". */
typedef unsigned long (*kallsyms_lookup_name_t)(const char *name);
static kallsyms_lookup_name_t su_kallsyms_lookup_name;

static bool su_tracepoint_registered;

/* ---- deferred elevation (Phase 2 fix #1, unchanged) ---- */

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

/* Called from the tracepoint probe context (rcu_read_lock_sched held) -
 * must not sleep. GFP_ATOMIC does not sleep. */
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

/* ---- symbol resolution (still needed by su_mount_isolate.c) ---- */

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

unsigned long su_lookup_symbol(const char *name)
{
	if (!su_kallsyms_lookup_name)
		return 0;
	return su_kallsyms_lookup_name(name);
}

/*
 * The tracepoint probe itself. Signature matches
 * TP_PROTO(struct task_struct *p, pid_t old_pid, struct linux_binprm *bprm)
 * from include/trace/events/sched.h's sched_process_exec event - `data`
 * is the opaque pointer passed to register_trace_sched_process_exec()
 * (unused here, we pass NULL at registration).
 *
 * Fires from begin_new_exec()/setup_new_exec() in fs/exec.c, AFTER the
 * new executable has been opened and bound to bprm->file - so
 * bprm->file->f_path is valid and d_path() gives a real, resolved,
 * canonical path. `p` is the exec'ing task itself (== current, since
 * this fires synchronously in that task's own exec path), so
 * current_uid() below is correct without needing `p` explicitly.
 */
static void su_exec_probe(void *data, struct task_struct *p, pid_t old_pid,
			   struct linux_binprm *bprm)
{
	char pathbuf[SU_EXEC_PATH_BUF];
	char *exec_path;
	uid_t uid;
	u32 flags;

	if (!bprm || !bprm->file)
		return;

	exec_path = d_path(&bprm->file->f_path, pathbuf, sizeof(pathbuf));
	if (IS_ERR(exec_path))
		return;

	/* Phase 4: one-shot boot hook, independent of the su-client check
	 * below (an exec_path can only match one of the two, but both
	 * checks are cheap string compares either way). */
	su_boot_trigger_check(exec_path);

	/*
	 * Phase 5: runs for EVERY execve, not gated on exec_path matching
	 * su_client - mount isolation is a per-task, per-app concern, not
	 * a su-request concern. su_mount_isolate_check() internally
	 * no-ops for allowlisted UIDs and for tasks already isolated, so
	 * this is cheap on the common (already-handled) path.
	 */
	uid = current_uid().val;
	su_mount_isolate_check(uid);

	if (strcmp(exec_path, SU_CLIENT_PATH) != 0)
		return;

	if (su_allowlist_check(uid, &flags)) {
		su_log("uid=%u has grant (flags=0x%x), queuing elevation\n", uid, flags);
		su_queue_elevate_current_task();
	} else {
		su_log("uid=%u not in allowlist, denying + notifying daemon\n", uid);
		su_notify_unknown_uid(uid);
	}
}

int su_hooks_install(void)
{
	int ret = su_resolve_kallsyms();

	if (ret < 0)
		return ret;

	/*
	 * sched_process_exec covers both native and compat (32-bit app)
	 * execve - it fires from the shared begin_new_exec() path both
	 * syscall entries funnel into, unlike the old do_execveat_common
	 * kprobe whose compat coverage was an unvalidated assumption (see
	 * git history's Phase 2 fix #5). Still worth confirming on a real
	 * 32-bit-capable device rather than treating this as settled.
	 */
	ret = register_trace_sched_process_exec(su_exec_probe, NULL);
	if (ret < 0) {
		su_err("failed to register sched_process_exec probe (ret=%d)\n", ret);
		return ret;
	}

	su_tracepoint_registered = true;
	su_log("hooked sched_process_exec tracepoint\n");
	return 0;
}

void su_hooks_uninstall(void)
{
	if (!su_tracepoint_registered)
		return;

	unregister_trace_sched_process_exec(su_exec_probe, NULL);
	/* Ensures no in-flight probe callback is still running on another
	 * CPU before the module's code is unmapped - without this,
	 * module_exit() could return (and the kernel could unload/free
	 * su_exec_probe's code) while another CPU is mid-callback,
	 * executing freed memory. */
	tracepoint_synchronize_unregister();
	su_tracepoint_registered = false;
}
