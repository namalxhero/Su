/*
 * su_uapi.h - Userspace API for the kernel <-> manager-app communication
 * channel. This header is shared between the kernel module and the
 * userspace manager app / daemon (via JNI or a native ioctl client).
 *
 * Only put things here that userspace needs to know: ioctl numbers,
 * fixed-size structs used to cross the kernel/userspace boundary.
 */
#ifndef _SU_UAPI_H
#define _SU_UAPI_H

#include <linux/types.h>
#include <linux/ioctl.h>

#define SU_DEVICE_NAME   "su_ctl"
#define SU_DEVICE_PATH   "/dev/" SU_DEVICE_NAME

#define SU_IOC_MAGIC     0xB4

/* Grant flags, stored per-UID in the kernel allowlist cache. */
#define SU_GRANT_NONE       0x0
#define SU_GRANT_PERMANENT  0x1  /* persistence itself lives in the manager
                                    app DB; kernel only knows "currently granted" */
#define SU_GRANT_SESSION    0x2  /* cleared automatically on module reload */

struct su_uid_entry {
	__u32 uid;
	__u32 flags;   /* SU_GRANT_* */
};

struct su_version_info {
	__u32 api_version;    /* bump whenever this struct / ioctl set changes */
	__u32 max_allowlist;  /* capacity of the kernel-side cache */
};

/* --- ioctl commands --- */

/* Add or update a UID entry in the kernel allowlist cache. */
#define SU_IOC_UID_GRANT     _IOW(SU_IOC_MAGIC, 1, struct su_uid_entry)

/* Remove a UID entry (revoke). */
#define SU_IOC_UID_REVOKE    _IOW(SU_IOC_MAGIC, 2, struct su_uid_entry)

/* Query whether a UID currently has a grant; flags field is filled in. */
#define SU_IOC_UID_QUERY     _IOWR(SU_IOC_MAGIC, 3, struct su_uid_entry)

/* Fetch module/API version + allowlist capacity. */
#define SU_IOC_GET_VERSION   _IOR(SU_IOC_MAGIC, 4, struct su_version_info)

/*
 * A blocking read() on SU_DEVICE_PATH delivers a struct su_uid_entry
 * describing the most recent "unknown UID requested su" event, so the
 * daemon can show an Allow/Deny prompt. Single-slot model for Phase 2 -
 * revisit with a real queue if concurrent requests matter in practice.
 */

#endif /* _SU_UAPI_H */
