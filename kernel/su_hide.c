/*
 * su_hide.c - Phase 5: path visibility control.
 *
 * Two mechanisms, both gated on the SAME primitive as su grants
 * (su_allowlist_check()) - see docs/PHASE5_DESIGN.md "Allowlist-check
 * reuse". A caller whose UID is in the allowlist is never affected by
 * either; everyone else gets ENOENT for hidden paths and filtered
 * directory listings.
 *
 *   1. vfs_statx() kretprobe - stat()/lstat()/fstatat()-family calls on
 *      a hidden path return -ENOENT instead of the real result.
 *   2. getdents64 kretprobe - directory listings have hidden-path
 *      entries spliced out of the already-filled result buffer, so
 *      `ls` and `stat` agree with each other (see design doc's
 *      "stat/listing mismatch" note - a known detection signal if this
 *      isn't done).
 *
 * CAVEAT (same standing caveat as the rest of this kernel tree): not
 * compiled/run against a real kernel. vfs_statx()'s signature varies by
 * version more than this file accounts for (see su_hooks_install_hide()
 * below) - this targets one common shape and documents that as a known
 * gap rather than pretending full coverage.
 */

#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/dirent.h>
#include <linux/file.h>
#include <linux/fs.h>
#include "include/su_common.h"

#define SU_HIDE_MAX_PATHS 32
#define SU_HIDE_PATH_MAX 256

struct su_hidden_path {
	char path[SU_HIDE_PATH_MAX];
	bool subtree; /* true: hide this path AND everything under it
			 (e.g. "/data/adb/modules") - false: exact file only */
};

static struct su_hidden_path hidden_paths[SU_HIDE_MAX_PATHS];
static int hidden_path_count;
static DEFINE_SPINLOCK(hidden_paths_lock);

/* ---- hidden-path list management ---- */

int su_hide_add_path(const char *path, bool subtree)
{
	unsigned long flags;
	int ret = -ENOSPC;

	if (!path || strnlen(path, SU_HIDE_PATH_MAX) >= SU_HIDE_PATH_MAX)
		return -EINVAL;

	spin_lock_irqsave(&hidden_paths_lock, flags);
	if (hidden_path_count < SU_HIDE_MAX_PATHS) {
		struct su_hidden_path *h = &hidden_paths[hidden_path_count];
		strscpy(h->path, path, SU_HIDE_PATH_MAX);
		h->subtree = subtree;
		hidden_path_count++;
		ret = 0;
	}
	spin_unlock_irqrestore(&hidden_paths_lock, flags);

	if (ret == 0)
		su_log("hiding path '%s'%s\n", path, subtree ? " (subtree)" : "");
	return ret;
}

int su_hide_remove_path(const char *path)
{
	unsigned long flags;
	int i, ret = -ENOENT;

	spin_lock_irqsave(&hidden_paths_lock, flags);
	for (i = 0; i < hidden_path_count; i++) {
		if (strcmp(hidden_paths[i].path, path) != 0)
			continue;
		/* swap-remove: order doesn't matter for this list */
		hidden_paths[i] = hidden_paths[hidden_path_count - 1];
		hidden_path_count--;
		ret = 0;
		break;
	}
	spin_unlock_irqrestore(&hidden_paths_lock, flags);

	return ret;
}

/* Cheap pre-check for the getdents64 return handler: is it even
 * possible for `dir_path` to contain a hidden entry? Avoids paying the
 * copy_from_user/scan/copy_to_user cost on every directory listing on
 * the system for the overwhelmingly common case where it can't. */
static bool su_hide_dir_may_have_hidden_children(const char *dir_path)
{
	unsigned long flags;
	bool maybe = false;
	int i;
	size_t dir_len = strlen(dir_path);

	spin_lock_irqsave(&hidden_paths_lock, flags);
	for (i = 0; i < hidden_path_count; i++) {
		const char *hp = hidden_paths[i].path;
		const char *slash = strrchr(hp, '/');
		size_t parent_len = slash ? (size_t)(slash - hp) : 0;

		/* dir_path is the exact parent directory of a hidden entry */
		if (parent_len == dir_len && strncmp(hp, dir_path, dir_len) == 0) {
			maybe = true;
			break;
		}
		/* dir_path is inside (or equal to) a hidden subtree - every
		 * listing under it is potentially affected */
		if (hidden_paths[i].subtree && dir_len >= strlen(hp) &&
		    strncmp(dir_path, hp, strlen(hp)) == 0) {
			maybe = true;
			break;
		}
	}
	spin_unlock_irqrestore(&hidden_paths_lock, flags);

	return maybe;
}

bool su_hide_path_is_hidden(const char *path)
{
	unsigned long flags;
	bool hit = false;
	int i;

	if (!path)
		return false;

	spin_lock_irqsave(&hidden_paths_lock, flags);
	for (i = 0; i < hidden_path_count; i++) {
		size_t plen = strlen(hidden_paths[i].path);

		if (strcmp(path, hidden_paths[i].path) == 0) {
			hit = true;
			break;
		}
		if (hidden_paths[i].subtree &&
		    strncmp(path, hidden_paths[i].path, plen) == 0 &&
		    path[plen] == '/') {
			hit = true;
			break;
		}
	}
	spin_unlock_irqrestore(&hidden_paths_lock, flags);

	return hit;
}

/*
 * Shared gate: true if `path` should be invisible to the CURRENT task.
 * Allowlisted callers always see the truth - this is what makes
 * visibility and su-grant the same decision (see design doc).
 */
static bool su_hide_should_hide_for_current(const char *path)
{
	if (!su_hide_path_is_hidden(path))
		return false;
	return !su_allowlist_check(current_uid().val, NULL);
}

/* ---- vfs_statx() hiding ---- */

/*
 * vfs_statx(int dfd, const char __user *filename, int flags, struct
 * kstat *stat, u32 request_mask) - this is the shape used by most
 * mainline-derived trees' stat-family syscalls since ~4.11. Entry
 * handler decides hide/no-hide and stashes it for the return handler,
 * since the actual ENOENT override has to happen on the way OUT (we
 * still want the real lookup to run so the kretprobe's return-handler
 * can simply discard its result, rather than trying to fabricate one).
 */
struct su_statx_probe_data {
	bool hide;
};

static int su_statx_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct su_statx_probe_data *data = (struct su_statx_probe_data *)ri->data;
	const char __user *ufilename = (const char __user *)regs->regs[1];
	char kpath[SU_HIDE_PATH_MAX];
	long n;

	data->hide = false;

	if (!ufilename)
		return 0;

	/*
	 * strncpy_from_user() can fault on the user page and is not
	 * guaranteed never to block in every configuration - same
	 * category of caveat as the rest of this file. Documented, not
	 * resolved, pending real-device validation.
	 */
	n = strncpy_from_user(kpath, ufilename, sizeof(kpath) - 1);
	if (n <= 0)
		return 0;
	kpath[n] = '\0';

	data->hide = su_hide_should_hide_for_current(kpath);
	return 0;
}

static int su_statx_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct su_statx_probe_data *data = (struct su_statx_probe_data *)ri->data;

	if (data->hide)
		regs_set_return_value(regs, (unsigned long)-ENOENT);

	return 0;
}

static struct kretprobe statx_kretprobe = {
	.kp.symbol_name = "vfs_statx",
	.entry_handler = su_statx_entry,
	.handler = su_statx_ret,
	.data_size = sizeof(struct su_statx_probe_data),
	.maxactive = 32,
};

/* ---- getdents64 directory-listing filtering ---- */

struct su_getdents_probe_data {
	struct linux_dirent64 __user *dirent_buf;
	char dir_path[SU_HIDE_PATH_MAX];
	bool dir_has_hidden_children; /* true only if dir_path itself might contain a hidden entry */
};

static int su_getdents_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct su_getdents_probe_data *data = (struct su_getdents_probe_data *)ri->data;
	int fd = (int)regs->regs[0];
	struct file *f;
	char pathbuf[SU_HIDE_PATH_MAX];
	char *resolved;

	data->dirent_buf = (struct linux_dirent64 __user *)regs->regs[1];
	data->dir_has_hidden_children = false;

	/* fget() on the calling task's own fd table - safe here since this
	 * entry_handler runs synchronously in the syscall-calling task's
	 * context, before the real getdents64 body executes. */
	f = fget(fd);
	if (!f)
		return 0;

	resolved = d_path(&f->f_path, pathbuf, sizeof(pathbuf));
	if (!IS_ERR(resolved)) {
		strscpy(data->dir_path, resolved, SU_HIDE_PATH_MAX);
		data->dir_has_hidden_children = su_hide_dir_may_have_hidden_children(resolved);
	} else {
		data->dir_path[0] = '\0';
		data->dir_has_hidden_children = false;
	}
	fput(f);

	return 0;
}

static int su_getdents_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct su_getdents_probe_data *data = (struct su_getdents_probe_data *)ri->data;
	long ret = regs_return_value(regs);
	char *kbuf;
	long copied;
	long read_off = 0, write_off = 0;

	if (ret <= 0 || !data->dir_has_hidden_children || data->dir_path[0] == '\0')
		return 0;
	if (!data->dirent_buf)
		return 0;
	if (su_allowlist_check(current_uid().val, NULL))
		return 0; /* allowlisted caller: see the real listing */

	kbuf = kmalloc(ret, GFP_ATOMIC);
	if (!kbuf)
		return 0; /* best-effort: can't filter without a scratch buffer, leave listing as-is */

	if (copy_from_user(kbuf, data->dirent_buf, ret)) {
		kfree(kbuf);
		return 0;
	}

	/*
	 * Walk the linux_dirent64 records, re-packing in place: entries
	 * whose full path (dir_path + "/" + d_name) is hidden are dropped,
	 * everything else is copied down to close the gap. d_reclen is
	 * each record's own length, so this is a standard compact-in-place
	 * pass over a variable-length-record buffer.
	 */
	while (read_off < ret) {
		struct linux_dirent64 *ent = (struct linux_dirent64 *)(kbuf + read_off);
		char full_path[SU_HIDE_PATH_MAX];
		bool drop;

		if (ent->d_reclen == 0)
			break; /* malformed - bail out rather than loop forever */

		snprintf(full_path, sizeof(full_path), "%s/%s", data->dir_path, ent->d_name);
		drop = su_hide_path_is_hidden(full_path);

		if (!drop) {
			if (write_off != read_off)
				memmove(kbuf + write_off, ent, ent->d_reclen);
			write_off += ent->d_reclen;
		}
		read_off += ent->d_reclen;
	}

	if (write_off != ret) {
		if (copy_to_user(data->dirent_buf, kbuf, write_off) == 0)
			regs_set_return_value(regs, (unsigned long)write_off);
		/* a copy_to_user failure here is left as-is (unfiltered
		 * listing) rather than risking a half-written user buffer
		 * with a mismatched return length. */
	}

	kfree(kbuf);
	return 0;
}

/*
 * Symbol name varies: arm64 syscall-ABI-wrapped name on recent kernels
 * vs. the plain name on older trees. Tried in order; first one that
 * resolves wins. Same "try a short list of known names" approach as
 * su_boot_trigger.c's placeholder, not a build-time detection like
 * Kbuild's SU_EXECVE_HAS_FD_ARG - tracked as a known gap (design doc).
 */
static const char *getdents_symbol_candidates[] = {
	"__arm64_sys_getdents64",
	"sys_getdents64",
};

static struct kretprobe getdents_kretprobe = {
	.entry_handler = su_getdents_entry,
	.handler = su_getdents_ret,
	.data_size = sizeof(struct su_getdents_probe_data),
	.maxactive = 32,
};

int su_hooks_install_hide(void)
{
	unsigned int i;
	int ret;

	ret = register_kretprobe(&statx_kretprobe);
	if (ret < 0) {
		su_err("failed to hook vfs_statx for path hiding (ret=%d)\n", ret);
		return ret;
	}

	for (i = 0; i < ARRAY_SIZE(getdents_symbol_candidates); i++) {
		getdents_kretprobe.kp.symbol_name = getdents_symbol_candidates[i];
		ret = register_kretprobe(&getdents_kretprobe);
		if (ret == 0)
			break;
	}
	if (ret < 0) {
		su_err("failed to hook getdents64 for listing filter (ret=%d)\n", ret);
		unregister_kretprobe(&statx_kretprobe);
		return ret;
	}

	su_log("path-hiding hooks installed (vfs_statx + %s)\n",
	       getdents_kretprobe.kp.symbol_name);
	return 0;
}

void su_hooks_uninstall_hide(void)
{
	unregister_kretprobe(&statx_kretprobe);
	unregister_kretprobe(&getdents_kretprobe);
}

int su_hide_init(void)
{
	hidden_path_count = 0;
	/* Default hidden set - the project's own artifacts. Module
	 * authors' own files aren't auto-added here (out of scope: a
	 * module could register additional hidden paths through a future
	 * ioctl, not implemented in this skeleton). */
	su_hide_add_path("/system/bin/su_client", false);
	su_hide_add_path("/data/adb/sud", false);
	su_hide_add_path("/data/adb/modules", true);
	return 0;
}

void su_hide_exit(void)
{
	hidden_path_count = 0;
}
