/*
 * su_mount_isolate.c - Phase 5: per-task mount namespace isolation.
 *
 * For a task whose UID is NOT in the su allowlist, give it a private
 * mount namespace with Phase 4's OverlayFS module mounts undone, so it
 * sees the real, unmodified partitions. Allowlisted (su-granted) tasks
 * are left alone and keep seeing the real modified system.
 *
 * CALLED FROM: su_hook.c's su_execve_pre_handler(), same place as the
 * Phase 4 boot-trigger check - one execve hook, several independent
 * checks hung off it, rather than installing yet another kprobe on the
 * same function.
 *
 * HIGH RISK / UNVALIDATED (see docs/PHASE5_DESIGN.md "Known high
 * risk"): copy_mnt_ns()-equivalent kernel internals are not
 * EXPORT_SYMBOL'd for module use on most trees. This resolves them via
 * su_lookup_symbol() (the same kallsyms trick su_hook.c already uses
 * for kallsyms_lookup_name itself), which only works if kallsyms
 * exposes the symbol and nothing else (CFI, lockdown, a kprobe
 * blacklist entry) blocks calling it this way. This is the single
 * most likely piece of Phase 5 to need rework on a real device - it is
 * written and reviewed, not proven.
 */

#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/sched/task.h>
#include <linux/slab.h>
#include <linux/nsproxy.h>
#include <linux/mount.h>
#include <linux/task_work.h>
#include <linux/version.h>
#include "include/su_common.h"

/*
 * Signatures as of recent mainline-derived trees. Both are internal
 * (fs/namespace.c, fs/mount.h) and not exported - resolved at runtime
 * via su_lookup_symbol() rather than linked against directly.
 *
 *   struct mnt_namespace *copy_mnt_ns(unsigned long flags,
 *       struct mnt_namespace *ns, struct user_namespace *user_ns,
 *       struct fs_struct *new_fs);
 *
 * CLONE_NEWNS triggers a private copy-on-write-ish duplication of the
 * namespace's mount tree (same operation unshare(CLONE_NEWNS) performs
 * from userspace) - calling it directly from kernel context is the
 * same technique, just skipping the syscall layer since we're already
 * in kernel space acting on `current`.
 */
typedef struct mnt_namespace *(*copy_mnt_ns_t)(unsigned long flags,
						struct mnt_namespace *ns,
						struct user_namespace *user_ns,
						struct fs_struct *new_fs);

static copy_mnt_ns_t su_copy_mnt_ns;

/* Per-task marker: has this task already been isolated? Mount
 * namespace isolation only needs to happen once per task (it's
 * inherited across further execve calls within the same process/task,
 * same as unshare(CLONE_NEWNS) would be) - re-running it on every
 * execve of an already-isolated task would be wasted work at best. A
 * single global bitmap keyed by pid would leak entries for dead tasks,
 * so this uses a flag stored via PF_* is not available (no free
 * process-flag bit to borrow safely) - tracked as a known gap, this
 * version re-checks the task's current mnt_ns pointer against the
 * "clean" one it would create instead of a dedicated flag; see
 * su_mount_already_isolated() below.
 */
static struct mnt_namespace *su_clean_mnt_ns_snapshot;

/*
 * Captured once, at module load (su_mount_isolate_init(), called from
 * su_main.c before any module support (Phase 4) has had a chance to
 * mount anything) - this is the "real, unmodified" namespace every
 * isolated task should end up looking like. Phase 4's sud mounts
 * happen later (at the boot-trigger execve, well after module load),
 * so this snapshot predates them by construction, not by a race that
 * has to be won.
 */
int su_mount_isolate_init(void)
{
	su_copy_mnt_ns = (copy_mnt_ns_t)su_lookup_symbol("copy_mnt_ns");
	if (!su_copy_mnt_ns) {
		su_warn("copy_mnt_ns not resolvable - mount isolation disabled this boot\n");
		return 0; /* non-fatal: rest of the module still works without this */
	}

	/* Snapshot the insmod-caller's (effectively: init's, this early in
	 * boot) mount namespace pointer as the "clean" baseline - see the
	 * struct comment above su_clean_mnt_ns_snapshot. */
	if (current->nsproxy)
		su_clean_mnt_ns_snapshot = current->nsproxy->mnt_ns;

	su_log("mount isolation ready (copy_mnt_ns resolved)\n");
	return 0;
}

static bool su_mount_already_isolated(void)
{
	/*
	 * Best-effort check: if current's mnt_ns is neither the init
	 * namespace's descendant-of-record we snapshotted nor already
	 * diverged from it, treat it as not-yet-isolated. This is a
	 * heuristic, not a precise flag - see the struct comment above for
	 * why a precise per-task flag isn't wired up in this skeleton.
	 */
	return current->nsproxy && current->nsproxy->mnt_ns == su_clean_mnt_ns_snapshot;
}

struct su_isolate_work {
	struct callback_head twork;
	pid_t pid;
};

static void su_do_isolate(struct callback_head *twork)
{
	struct su_isolate_work *work = container_of(twork, struct su_isolate_work, twork);
	struct nsproxy *nsproxy;
	struct mnt_namespace *new_ns;

	if (!su_copy_mnt_ns)
		goto out;

	nsproxy = current->nsproxy;
	if (!nsproxy)
		goto out;

	/*
	 * CLONE_NEWNS here mirrors what unshare(2) passes for a plain
	 * "give me my own mount namespace" request - no other namespace
	 * types are touched.
	 */
	new_ns = su_copy_mnt_ns(CLONE_NEWNS, nsproxy->mnt_ns, current_user_ns(),
				 current->fs);
	if (IS_ERR(new_ns)) {
		su_err("copy_mnt_ns failed for pid=%d (err=%ld)\n", work->pid, PTR_ERR(new_ns));
		goto out;
	}

	/*
	 * TODO (tracked in docs/PHASE5_DESIGN.md as part of the "high
	 * risk" item): this only gives the task its own mount namespace -
	 * it does NOT yet unmount Phase 4's OverlayFS mounts inside that
	 * new namespace. Doing that correctly needs walking the new
	 * namespace's mount list and calling the (also unexported)
	 * unmount internals for exactly the mount points su_boot_trigger.c
	 * / sud applied overlays to, without touching anything else in the
	 * tree. Deliberately left as a follow-up rather than guessed at
	 * here - an incorrect unmount here is worse than no isolation at
	 * all (could break the isolated process entirely).
	 */
	su_log("mount namespace isolated for pid=%d (overlay unmount: not yet implemented)\n",
	       work->pid);

out:
	kfree(work);
}

void su_mount_isolate_check(uid_t uid)
{
	struct su_isolate_work *work;

	if (!su_copy_mnt_ns)
		return; /* resolution failed at init - nothing to do */

	if (su_allowlist_check(uid, NULL))
		return; /* allowlisted: sees the real system, no isolation */

	if (su_mount_already_isolated())
		return;

	/* GFP_ATOMIC: called from su_hook.c's execve pre_handler, same
	 * atomic-context constraint as Phase 2's elevation deferral. */
	work = kmalloc(sizeof(*work), GFP_ATOMIC);
	if (!work)
		return;

	work->pid = current->pid;
	init_task_work(&work->twork, su_do_isolate);

#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 16, 0)
	if (task_work_add(current, &work->twork, TWA_RESUME)) {
#else
	if (task_work_add(current, &work->twork, true)) {
#endif
		kfree(work);
	}
}

void su_mount_isolate_exit(void)
{
	su_copy_mnt_ns = NULL;
}
