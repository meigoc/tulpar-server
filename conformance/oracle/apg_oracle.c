/*
 * Test oracle for differential conformance testing of Tulpar Server against
 * libapg. Links the public libapg API and prints machine-readable JSON.
 *
 * Derived from libapg behavior at commit 1ecebf7c7fb567740126bed483779f536b57c36a
 * (v2.5.0): parse_package (src/package.c), package_metadata_from_file (src/json.c),
 * ver_compare / dep_constraint_parse (src/version/version.c), keyring_load /
 * keyring_verify (src/sign/sodium/keyring.c), unarchive_package_in_root and
 * archive_last_error (src/archive.c).
 *
 * This tool is test infrastructure. It is never shipped in the server jar and
 * never runs as part of the hermetic test suite; see conformance/README.md.
 *
 * Subcommands:
 *   parse    <pkg.apg> <scratch-root>          full libapg acceptance verdict
 *   install  <pkg.apg> <scratch-root>          parse + install_package_in_root
 *   extract  <pkg.apg> <scratch-root>          archive extraction only
 *   vercmp   <a> <b>                           ver_compare signum
 *   depparse <constraint>                      dep_constraint_parse, canonicalized
 *   keyring  <keyring-dir> <pkg> <sig>         keyring_load + keyring_verify
 *
 * Every mode prints exactly one JSON object on stdout and exits 0 (verdicts are
 * data, not exit codes), except on usage errors (exit 2).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <apg/package.h>
#include <apg/version.h>
#include <apg/keyring.h>
#include <apg/archive.h>

static void json_escape(const char *s)
{
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)s; *p; p++)
    {
        switch (*p)
        {
        case '"':  fputs("\\\"", stdout); break;
        case '\\': fputs("\\\\", stdout); break;
        case '\b': fputs("\\b", stdout);  break;
        case '\f': fputs("\\f", stdout);  break;
        case '\n': fputs("\\n", stdout);  break;
        case '\r': fputs("\\r", stdout);  break;
        case '\t': fputs("\\t", stdout);  break;
        default:
            if (*p < 0x20)
                printf("\\u%04x", *p);
            else
                putchar(*p);
        }
    }
    putchar('"');
}

static void json_str_field(const char *key, const char *val, int *first)
{
    printf("%s\"%s\":", *first ? "" : ",", key);
    *first = 0;
    if (val)
        json_escape(val);
    else
        fputs("null", stdout);
}

static void json_bool_field(const char *key, bool val, int *first)
{
    printf("%s\"%s\":%s", *first ? "" : ",", key, val ? "true" : "false");
    *first = 0;
}

static void json_str_list(const char *key, const struct str_list *list, int *first)
{
    printf("%s\"%s\":[", *first ? "" : ",", key);
    *first = 0;
    int out = 0;
    for (int i = 0; i < list->count; i++)
    {
        if (!list->items || !list->items[i])
            continue;
        if (out++)
            putchar(',');
        json_escape(list->items[i]);
    }
    putchar(']');
}

static void json_dep_list(const char *key, const struct dep_constraint_list *list,
                          int *first)
{
    printf("%s\"%s\":[", *first ? "" : ",", key);
    *first = 0;
    int out = 0;
    for (int i = 0; i < list->count; i++)
    {
        if (!list->items)
            break;
        char *s = dep_constraint_to_str(&list->items[i]);
        if (!s)
            continue;
        if (out++)
            putchar(',');
        json_escape(s);
        free(s);
    }
    putchar(']');
}

static void print_meta_fields(struct package *p, int *first)
{
    struct package_metadata *m = p->meta;
    json_str_field("name", m->name, first);
    json_str_field("version", m->version, first);
    json_str_field("type", m->type, first);
    json_str_field("architecture", m->architecture, first);
    json_str_field("description", m->description, first);
    json_str_field("maintainer", m->maintainer, first);
    json_str_field("license", m->license, first);
    json_str_field("homepage", m->homepage, first);
    json_str_list("tags", &m->tags, first);
    json_dep_list("dependencies", &m->dependencies, first);
    json_str_list("conflicts", &m->conflicts, first);
    json_str_list("provides", &m->provides, first);
    json_str_list("replaces", &m->replaces, first);
    json_str_list("conf", &m->conf, first);
}

static int cmd_parse(const char *pkg, const char *root)
{
    struct package *p = parse_package(pkg, root);
    const char *err = archive_last_error();
    int first = 1;
    putchar('{');
    json_bool_field("accepted", p != NULL, &first);
    json_str_field("archive_error", err, &first);
    if (p && p->meta)
        print_meta_fields(p, &first);
    if (p)
        package_free(p);
    fputs("}\n", stdout);
    return 0;
}

static int cmd_install(const char *pkg, const char *root)
{
    struct package *p = parse_package(pkg, root);
    int first = 1;
    putchar('{');
    json_bool_field("accepted", p != NULL, &first);
    if (p)
    {
        bool installed = install_package_in_root(p, root);
        json_bool_field("installed", installed, &first);
        if (p->meta)
            print_meta_fields(p, &first);
        package_free(p);
    }
    fputs("}\n", stdout);
    return 0;
}

static int cmd_extract(const char *pkg, const char *root)
{
    struct package *p = package_new();
    if (!p)
    {
        fputs("{\"extracted\":false,\"error\":\"oom\"}\n", stdout);
        return 0;
    }
    p->pkg_path = strdup(pkg);
    bool ok = unarchive_package_in_root(p, root);
    const char *err = archive_last_error();
    int first = 1;
    putchar('{');
    json_bool_field("extracted", ok, &first);
    json_str_field("error", err, &first);
    fputs("}\n", stdout);
    package_free(p);
    return 0;
}

static int cmd_vercmp(const char *a, const char *b)
{
    int c = ver_compare(a, b);
    printf("{\"cmp\":%d}\n", (c > 0) - (c < 0));
    return 0;
}

static int cmd_depparse(const char *s)
{
    struct dep_constraint c = dep_constraint_parse(s);
    int first = 1;
    putchar('{');
    json_str_field("name", c.name, &first);
    char *canon = dep_constraint_to_str(&c);
    json_str_field("canonical", canon, &first);
    json_str_field("version", c.version, &first);
    fputs("}\n", stdout);
    free(canon);
    dep_constraint_free(&c);
    return 0;
}

static int cmd_keyring(const char *dir, const char *pkg, const char *sig)
{
    struct keyring *kr = keyring_load(dir);
    bool ok = kr ? keyring_verify(kr, pkg, sig) : false;
    int first = 1;
    putchar('{');
    json_bool_field("keyring_loaded", kr != NULL, &first);
    json_bool_field("verified", ok, &first);
    fputs("}\n", stdout);
    keyring_free(kr);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc >= 2 && !strcmp(argv[1], "parse") && argc == 4)
        return cmd_parse(argv[2], argv[3]);
    if (argc >= 2 && !strcmp(argv[1], "install") && argc == 4)
        return cmd_install(argv[2], argv[3]);
    if (argc >= 2 && !strcmp(argv[1], "extract") && argc == 4)
        return cmd_extract(argv[2], argv[3]);
    if (argc >= 2 && !strcmp(argv[1], "vercmp") && argc == 4)
        return cmd_vercmp(argv[2], argv[3]);
    if (argc >= 2 && !strcmp(argv[1], "depparse") && argc == 3)
        return cmd_depparse(argv[2]);
    if (argc >= 2 && !strcmp(argv[1], "keyring") && argc == 5)
        return cmd_keyring(argv[2], argv[3], argv[4]);

    fprintf(stderr,
            "usage:\n"
            "  apg_oracle parse <pkg.apg> <scratch-root>\n"
            "  apg_oracle install <pkg.apg> <scratch-root>\n"
            "  apg_oracle extract <pkg.apg> <scratch-root>\n"
            "  apg_oracle vercmp <a> <b>\n"
            "  apg_oracle depparse <constraint>\n"
            "  apg_oracle keyring <keyring-dir> <pkg> <sig>\n");
    return 2;
}
