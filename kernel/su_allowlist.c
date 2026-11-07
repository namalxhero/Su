/*
 * su_allowlist.c - Hybrid allowlist cache.
 *
 * Design (see docs/PHASE1_DESIGN.md):
 *   - Small fixed-size in-kernel array of {uid, flags}, protected by a
 *     spinlock, checked on the hot path (every su_hook.c elevation
 *     decision). No userspace round-trip on the fast path.
 *   - The manager-app daemon is the source of truth for *persistent*
 *     policy; it pushes grants/revokes into this cache via ioctl
 *     (SU_IOC_UID_GRANT / SU_IOC_UID_REVOKE) at boot and whenever the
 *     user changes the allowlist in the UI.
 *   - When su_hook.c sees a UID that is NOT in this cache, it does NOT
 *     block waiting for a decision (see Phase 1: "default deny +
 *     manual retry"). It denies immediately and records the UID here
 *     as a single-slot "pending request" that the daemon picks up via
 *     a blocking read / poll on the device node.
 */

#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/wait.h>
#include <linux/string.h>
#include "su_common.h"

struct su_allow_entry {
	uid_t uid;
	u32   flags;
	bool  used;
};

static struct su_allow_entry allow_table[SU_MAX_ALLOWLIST];
static DEFINE_SPINLOCK(allow_lock);
static int allow_count;

/* single-slot pending "unknown uid requested su" notification */
DEFINE_WAIT_QUEUE_HEAD(su_request_waitq);
static uid_t pending_uid;
static bool  pending_valid;
static DEFINE_SPINLOCK(pending_lock);

int su_allowlist_init(void)
{
	memset(allow_table, 0, sizeof(allow_table));
	allow_count = 0;
	pending_valid = false;
	su_log("allowlist initialised (capacity=%d)\n", SU_MAX_ALLOWLIST);
	return 0;
}

void su_allowlist_exit(void)
{
	/* nothing to free, table is static */
}

int su_allowlist_grant(uid_t uid, u32 flags)
{
	int i, free_slot = -1;
	unsigned long irqflags;

	spin_lock_irqsave(&allow_lock, irqflags);
	for (i = 0; i < SU_MAX_ALLOWLIST; i++) {
		if (allow_table[i].used && allow_table[i].uid == uid) {
			allow_table[i].flags = flags;
			spin_unlock_irqrestore(&allow_lock, irqflags);
			su_log("uid=%u grant updated (flags=0x%x)\n", uid, flags);
			return 0;
		}
		if (!allow_table[i].used && free_slot < 0)
			free_slot = i;
	}

	if (free_slot < 0) {
		spin_unlock_irqrestore(&allow_lock, irqflags);
		su_warn("allowlist full, cannot grant uid=%u\n", uid);
		return -ENOSPC;
	}

	allow_table[free_slot].uid = uid;
	allow_table[free_slot].flags = flags;
	allow_table[free_slot].used = true;
	allow_count++;
	spin_unlock_irqrestore(&allow_lock, irqflags);

	su_log("uid=%u granted (flags=0x%x)\n", uid, flags);
	return 0;
}

int su_allowlist_revoke(uid_t uid)
{
	int i;
	unsigned long irqflags;

	spin_lock_irqsave(&allow_lock, irqflags);
	for (i = 0; i < SU_MAX_ALLOWLIST; i++) {
		if (allow_table[i].used && allow_table[i].uid == uid) {
			allow_table[i].used = false;
			allow_count--;
			spin_unlock_irqrestore(&allow_lock, irqflags);
			su_log("uid=%u revoked\n", uid);
			return 0;
		}
	}
	spin_unlock_irqrestore(&allow_lock, irqflags);
	return -ENOENT;
}

bool su_allowlist_check(uid_t uid, u32 *flags_out)
{
	int i;
	unsigned long irqflags;
	bool found = false;

	spin_lock_irqsave(&allow_lock, irqflags);
	for (i = 0; i < SU_MAX_ALLOWLIST; i++) {
		if (allow_table[i].used && allow_table[i].uid == uid) {
			if (flags_out)
				*flags_out = allow_table[i].flags;
			found = true;
			break;
		}
	}
	spin_unlock_irqrestore(&allow_lock, irqflags);
	return found;
}

int su_allowlist_count(void)
{
	return allow_count;
}

void su_notify_unknown_uid(uid_t uid)
{
	unsigned long irqflags;

	spin_lock_irqsave(&pending_lock, irqflags);
	pending_uid = uid;
	pending_valid = true;
	spin_unlock_irqrestore(&pending_lock, irqflags);

	wake_up_interruptible(&su_request_waitq);
}

bool su_pop_pending_request(uid_t *uid_out)
{
	unsigned long irqflags;
	bool had_one;

	spin_lock_irqsave(&pending_lock, irqflags);
	had_one = pending_valid;
	if (had_one && uid_out)
		*uid_out = pending_uid;
	pending_valid = false;
	spin_unlock_irqrestore(&pending_lock, irqflags);

	return had_one;
}
