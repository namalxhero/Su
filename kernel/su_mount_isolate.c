/*
 * su_mount_isolate.c - Phase 5: per-task mount namespace isolation.
 *
 * For a task whose UID is NOT in the su allowlist, give it a private
 * mount namespace with Phase 4's OverlayFS module mounts popped back
 * off (revealing the real, unmodified partition stacked underneath -
 * see "Why a plain umount is enough" below), so it sees the real
 * system. Allowlisted (su-granted) tasks are left alone.
 *
 * CALLED FROM: su_hook.c's su_execve_pre_handler(), same place as the
 * Phase 4 boot-trigger check - one execve hook, several independent
 * checks hung off it, rather than installing yet another kprobe on the
 * same function.
 *
 * HIGH RISK / UNVALIDATED (see docs/PHASE5_DESIGN.md "Known high
 * risk"): every kernel function this file calls to actually do the
 * isolation - create_new_namespaces(), switch_task_namespaces(),
 * path_umount() - is an internal (fs/namespace.c, kernel/nsproxy.c)
 * symbol, not EXPORT_SYMBOL'd for module use on most trees. All three
 * are resolved via su_lookup_symbol() (the same kallsyms trick
 * su_hook.c uses for kallsyms_lookup_name itself). This only works if
 * kallsyms exposes each symbol and nothing else (CFI, lockdown, a
 * kprobe blacklist entry) blocks calling it this way - and
 * path_umount()'s own signature/ownership-of-the-struct-path-it-was-
 * given has changed shape across kernel versions (older trees expose
 * the equivalent operation as do_umount(struct vfsmount *, int)
 * instead - NOT handled here, see su_unmount_overlay_targets() below).
 * This remains the single most likely piece of Phase 5 to need rework
 * on a real device - written and reviewed, not proven.
 */

#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/nsproxy.h>
#include <linux/mount.h>
#include <linux/namei.h>
#include <linux/task_work.h>
#include <linux/version.h>
#include <linux/err.h>
#include <linux/fs.h>
/* See su_hook.c's identical comment: linux/sched/task.h only exists
 * from ~v4.11 onward, must come after linux/version.h is available. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
#include <linux/sched/task.h>
#endif
#include "include/su_common.h"

/*
 * Mirrors daemon/sud.c's overlay_targets[] - the mount points Phase 4
 * actually overlays. Duplicated here rather than shared (kernel module
 * and standalone userspace daemon have no common header/config today) -
 * same category of problem Phase 3's su_uapi.h duplication was, before
 * that got a build-time sync step. No such step exists for this pair
 * yet; if sud.c's list changes, this one has to be updated by hand.
 * Tracked as a known gap, not solved here.
 */
static const char *su_overlay_targets[] = {
	"/system",
};
#define NUM_SU_OVERLAY_TARGETS ARRAY_SIZE(su_overlay_targets)

/*
 *   struct nsproxy *create_new_namespaces(unsigned long flags,
 *       struct task_struct *tsk, struct user_namespace *user_ns,
 *       struct fs_struct *new_fs);
 *
 * The actual internal unshare(CLONE_NEWNS) does this (not just
 * copy_mnt_ns() alone, which produces a new mnt_namespace but doesn't
 * build/install the nsproxy wrapper around it) - this returns a ready
 * nsproxy with everything else shared and only mnt_ns new.
 */
typedef struct nsproxy *(*create_new_namespaces_t)(unsigned long flags,
						     struct task_struct *tsk,
						     struct user_namespace *user_ns,
						     struct fs_struct *new_fs);

/*
 *   void switch_task_namespaces(struct task_struct *p, struct nsproxy *new);
 *
 * Installs the nsproxy create_new_namespaces() built onto the task,
 * releasing the old one. Also internal/unexported on most trees.
 */
typedef void (*switch_task_namespaces_t)(struct task_struct *p, struct nsproxy *new);

/*
 *   int path_umount(struct path *path, int flags);
 *
 * The refactored (post ~5.9) internal body of the umount(2) syscall,
 * taking an already-resolved struct path instead of a user pointer.
 * Older trees instead expose do_umount(struct vfsmount *, int) with a
 * different calling convention - NOT handled by this version (see
 * su_unmount_overlay_targets()'s fallback warning).
 */
typedef int (*path_umount_t)(struct path *path, int flags);

static create_new_namespaces_t su_create_new_namespaces;
static switch_task_namespaces_t su_switch_task_namespaces;
static path_umount_t su_path_umount;

/* Snapshot of the insmod-caller's (effectively init's, this early in
 * boot) mount namespace, captured at module load - see
 * su_mount_isolate_init(). Used both as the "already isolated?" check
 * (su_mount_already_isolated()) and conceptually as the baseline every
 * isolated task should end up seeing a view equivalent to. */
static struct mnt_namespace *su_clean_mnt_ns_snapshot;

int su_mount_isolate_init(void)
{
	su_create_new_namespaces =
		(create_new_namespaces_t)su_lookup_symbol("create_new_namespaces");
	su_switch_task_namespaces =
		(switch_task_namespaces_t)su_lookup_symbol("switch_task_namespaces");
	su_path_umount = (path_umount_t)su_lookup_symbol("path_umount");

	if (!su_create_new_namespaces || !su_switch_task_namespaces) {
		su_warn("create_new_namespaces/switch_task_namespaces not resolvable - "
			"mount isolation disabled this boot\n");
		return 0; /* non-fatal: rest of the module still works without this */
	}
	if (!su_path_umount) {
		su_warn("path_umount not resolvable (older kernel tree? do_umount() "
			"fallback not implemented) - namespaces will be created but "
			"overlay mounts will NOT be popped, isolation will be a no-op\n");
		/* still proceed: namespace creation below will "succeed" but
		 * su_unmount_overlay_targets() will log-and-skip per target */
	}

	if (current->nsproxy)
		su_clean_mnt_ns_snapshot = current->nsproxy->mnt_ns;

	su_log("mount isolation ready (create_new_namespaces=%s, path_umount=%s)\n",
	       su_create_new_namespaces ? "resolved" : "MISSING",
	       su_path_umount ? "resolved" : "MISSING");
	return 0;
}

static bool su_mount_already_isolated(void)
{
	/*
	 * Heuristic, not a precise per-task flag (see Phase 5 design doc /
	 * earlier revision of this file for why): if current's mnt_ns is
	 * still the snapshotted "clean" one, it hasn't been isolated yet.
	 * Once switch_task_namespaces() installs a freshly-created nsproxy
	 * below, current->nsproxy->mnt_ns will differ from the snapshot
	 * (it's a distinct copy, even though both started identical), so
	 * this correctly recognises "already done" on the task's next exec.
	 */
	return current->nsproxy && current->nsproxy->mnt_ns == su_clean_mnt_ns_snapshot;
}

/*
 * Why a plain umount is enough: sud (daemon/overlay_mount.c) applies
 * each overlay with a single mount(2) call AT the real mount point
 * (e.g. mount("overlay", "/system", "overlay", ...)) - it does not
 * replace or remove the underlying real partition's own mount entry,
 * it stacks the overlay ON TOP of it at that mountpoint. Mount stacking
 * means umounting the top entry at a mountpoint reveals whatever was
 * mounted there before - in this case, the original /system mount.
 * So "undo the overlay" and "unmount /system once, inside the isolated
 * namespace" are the same operation; no need to walk the mount tree
 * looking for anything overlay-specific.
 *
 * This runs AFTER switch_task_namespaces() has installed the new
 * nsproxy on `current`, so kern_path()/path_umount() below resolve and
 * act within the new (isolated) namespace automatically - no explicit
 * "enter namespace" step needed, since lookups are always relative to
 * current's own nsproxy.
 */
static void su_unmount_overlay_targets(pid_t pid)
{
	unsigned int i;

	if (!su_path_umount)
		return; /* already warned once at init - don't spam per-task */

	for (i = 0; i < NUM_SU_OVERLAY_TARGETS; i++) {
		struct path path;
		int err = kern_path(su_overlay_targets[i], LOOKUP_FOLLOW, &path);

		if (err) {
			su_warn("kern_path(%s) failed for pid=%d (err=%d) - skipping\n",
				su_overlay_targets[i], pid, err);
			continue;
		}

		/*
		 * MNT_DETACH (lazy unmount): avoids EBUSY if something
		 * already has an open fd under the overlay from before
		 * isolation happened. The old mount stays alive until the
		 * last reference drops, invisible to new lookups from this
		 * namespace from this point on - acceptable here since the
		 * goal is "new lookups see the real partition", not
		 * "instantly free every resource".
		 *
		 * CAVEAT: whether path_umount() calls path_put(&path)
		 * itself (consuming it) or expects the caller to is
		 * version-dependent and not verified against a real tree
		 * here - path_put() is called unconditionally below on the
		 * assumption it does not, which risks a double-put on
		 * trees where it does. Flagged, not resolved - see file
		 * header.
		 */
		err = su_path_umount(&path, MNT_DETACH);
		path_put(&path);

		if (err)
			su_warn("path_umount(%s) failed for pid=%d (err=%d)\n",
				su_overlay_targets[i], pid, err);
		else
			su_log("popped overlay at %s for pid=%d\n", su_overlay_targets[i], pid);
	}
}

struct su_isolate_work {
	struct callback_head twork;
	pid_t pid;
};

static void su_do_isolate(struct callback_head *twork)
{
	struct su_isolate_work *work = container_of(twork, struct su_isolate_work, twork);
	struct nsproxy *new_nsproxy;

	if (!su_create_new_namespaces || !su_switch_task_namespaces)
		goto out;

	/*
	 * CLONE_NEWNS mirrors what unshare(2) passes for a plain "give me
	 * my own mount namespace" request - no other namespace types are
	 * touched. current->fs is passed through unchanged (same cwd/root
	 * semantics as before, just a private mount table from here on).
	 */
	new_nsproxy = su_create_new_namespaces(CLONE_NEWNS, current, current_user_ns(),
						current->fs);
	if (IS_ERR(new_nsproxy)) {
		su_err("create_new_namespaces failed for pid=%d (err=%ld)\n",
		       work->pid, PTR_ERR(new_nsproxy));
		goto out;
	}

	su_switch_task_namespaces(current, new_nsproxy);

	su_unmount_overlay_targets(work->pid);

	su_log("mount namespace isolated for pid=%d\n", work->pid);

out:
	kfree(work);
}

void su_mount_isolate_check(uid_t uid)
{
	struct su_isolate_work *work;

	if (!su_create_new_namespaces || !su_switch_task_namespaces)
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
	su_create_new_namespaces = NULL;
	su_switch_task_namespaces = NULL;
	su_path_umount = NULL;
}
