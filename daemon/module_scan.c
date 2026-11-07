/*
 * module_scan.c - discover + parse enabled Magisk-format modules.
 *
 * See docs/PHASE4_DESIGN.md "Module layout (Magisk-compatible)" for the
 * on-disk format this reads.
 */

#include <dirent.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "sud.h"

static bool path_exists(const char *path)
{
	struct stat st;
	return stat(path, &st) == 0;
}

static void join(char *out, size_t out_sz, const char *a, const char *b)
{
	snprintf(out, out_sz, "%s/%s", a, b);
}

bool sud_parse_module_prop(const char *module_dir, struct sud_module *mod)
{
	char prop_path[SUD_MAX_PATH];
	FILE *f;
	char line[512];

	join(prop_path, sizeof(prop_path), module_dir, "module.prop");
	f = fopen(prop_path, "r");
	if (!f)
		return false;

	mod->id[0] = mod->name[0] = mod->version[0] = '\0';

	while (fgets(line, sizeof(line), f)) {
		char *eq, *key, *val, *nl;

		nl = strchr(line, '\n');
		if (nl)
			*nl = '\0';

		eq = strchr(line, '=');
		if (!eq)
			continue;
		*eq = '\0';
		key = line;
		val = eq + 1;

		/*
		 * module.prop intentionally has no quoting/escaping rules
		 * in the Magisk format - a raw key=value line is the whole
		 * format. We just copy the value as-is, same as every
		 * other implementation of this parser.
		 */
		if (strcmp(key, "id") == 0)
			snprintf(mod->id, sizeof(mod->id), "%s", val);
		else if (strcmp(key, "name") == 0)
			snprintf(mod->name, sizeof(mod->name), "%s", val);
		else if (strcmp(key, "version") == 0)
			snprintf(mod->version, sizeof(mod->version), "%s", val);
	}

	fclose(f);

	/* id= is the one field this daemon actually depends on (it's used
	 * for logging and, later, for skip/disable bookkeeping) - treat a
	 * module.prop without it as unparseable rather than silently
	 * falling back to the directory name, since a module's scripts may
	 * assume `id` elsewhere. */
	return mod->id[0] != '\0';
}

int sud_scan_modules(struct sud_module *out, int max)
{
	DIR *dir = opendir(SUD_MODULES_DIR);
	struct dirent *ent;
	int count = 0;

	if (!dir)
		return -1;

	while (count < max && (ent = readdir(dir)) != NULL) {
		char module_dir[SUD_MAX_PATH];
		char marker[SUD_MAX_PATH];

		if (ent->d_name[0] == '.')
			continue; /* skip ".", "..", and dotfiles */

		join(module_dir, sizeof(module_dir), SUD_MODULES_DIR, ent->d_name);

		join(marker, sizeof(marker), module_dir, "disable");
		if (path_exists(marker))
			continue;
		join(marker, sizeof(marker), module_dir, "remove");
		if (path_exists(marker))
			continue;

		struct sud_module *mod = &out[count];
		if (!sud_parse_module_prop(module_dir, mod))
			continue; /* unparseable module.prop - skip, don't abort the whole scan */

		snprintf(mod->dir_name, sizeof(mod->dir_name), "%s", ent->d_name);

		join(marker, sizeof(marker), module_dir, "skip_mount");
		mod->skip_mount = path_exists(marker);

		join(marker, sizeof(marker), module_dir, "post-fs-data.sh");
		mod->has_post_fs_data_sh = path_exists(marker);

		join(marker, sizeof(marker), module_dir, "service.sh");
		mod->has_service_sh = path_exists(marker);

		count++;
	}

	closedir(dir);
	return count;
}
