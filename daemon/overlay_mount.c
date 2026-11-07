/*
 * overlay_mount.c - stack enabled modules' system/ trees over the real
 * partitions via OverlayFS.
 *
 * See docs/PHASE4_DESIGN.md "OverlayFS mount shape". Read-only only for
 * now (no upperdir/workdir) - see that doc's "Known gaps".
 */

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>

#include "sud.h"

static bool module_has_mount_point(const char *module_dir, const char *mount_point,
				    char *out_lower, size_t out_sz)
{
	/*
	 * A module's system/ tree mirrors the real partition layout, e.g.
	 * system/system/bin/foo overlays /system/bin/foo when mount_point
	 * is "/system". mount_point is always an absolute path starting
	 * with '/', so module_dir + "/system" + mount_point gives the
	 * module's matching subdirectory directly.
	 */
	char candidate[SUD_MAX_PATH];
	struct stat st;

	snprintf(candidate, sizeof(candidate), "%s/system%s", module_dir, mount_point);
	if (stat(candidate, &st) != 0 || !S_ISDIR(st.st_mode))
		return false;

	snprintf(out_lower, out_sz, "%s", candidate);
	return true;
}

int sud_overlay_apply(const struct sud_module *modules, int count,
		       const char *mount_point)
{
	/*
	 * lowerdir= is a colon-separated list, highest-priority layer
	 * first. We build it as: [modules in caller-supplied order] then
	 * the real mount_point itself as the lowest/base layer. Caller
	 * (sud.c) is responsible for passing modules[] in the order it
	 * wants precedence applied (see docs/PHASE4_DESIGN.md).
	 */
	char lowerdir[4096];
	size_t used = 0;
	int layers = 0;
	int i;

	lowerdir[0] = '\0';

	for (i = 0; i < count; i++) {
		char layer[SUD_MAX_PATH];
		char module_dir[SUD_MAX_PATH];
		int n;

		if (modules[i].skip_mount)
			continue;

		snprintf(module_dir, sizeof(module_dir), "%s/%s", SUD_MODULES_DIR,
			 modules[i].dir_name);

		if (!module_has_mount_point(module_dir, mount_point, layer, sizeof(layer)))
			continue;

		n = snprintf(lowerdir + used, sizeof(lowerdir) - used, "%s%s",
			     layers > 0 ? ":" : "", layer);
		if (n < 0 || (size_t)n >= sizeof(lowerdir) - used) {
			fprintf(stderr, "sud: lowerdir option too long for %s, truncating module list\n",
				mount_point);
			break;
		}
		used += (size_t)n;
		layers++;
	}

	if (layers == 0)
		return 0; /* no module touches this mount point - nothing to do */

	/* base layer: the real, un-overlaid partition */
	{
		int n = snprintf(lowerdir + used, sizeof(lowerdir) - used, ":%s", mount_point);
		if (n < 0 || (size_t)n >= sizeof(lowerdir) - used) {
			fprintf(stderr, "sud: lowerdir option too long for %s, aborting overlay\n",
				mount_point);
			return -1;
		}
	}

	char opts[sizeof(lowerdir) + 32];
	snprintf(opts, sizeof(opts), "lowerdir=%s", lowerdir);

	/*
	 * MS_RDONLY: no upperdir/workdir yet (see Known gaps in the
	 * design doc) - a module that writes to its overlaid files at
	 * runtime isn't supported by this version.
	 */
	if (mount("overlay", mount_point, "overlay", MS_RDONLY, opts) != 0) {
		fprintf(stderr, "sud: overlay mount failed for %s: %s\n",
			mount_point, strerror(errno));
		return -1;
	}

	fprintf(stderr, "sud: overlaid %s with %d module layer(s)\n", mount_point, layers);
	return 0;
}
