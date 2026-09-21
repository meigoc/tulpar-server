/*
 * Signature vector generator for differential testing of Tulpar Server's
 * detached-signature verifier against libAPG.
 *
 * libAPG signs/verifies packages with libsodium's STREAMING API
 * (crypto_sign_init/update/final_create/final_verify in src/sign/sodium/).
 * That API is Ed25519ph (RFC 8032 prehashed Ed25519): the signed message is
 * SHA-512(pkg) under the dom2(1,"") domain separator, NOT the package bytes
 * directly. A plain-Ed25519 verifier therefore rejects every genuine libAPG
 * signature; this tool produces authentic libAPG-compatible vectors so the
 * JVM verifier can be proven equivalent.
 *
 * Derived from libAPG at the commit pinned in docs/libapg-compat.md. Test
 * tooling only; never shipped in the server jar.
 *
 * Usage:
 *   apg_sigtool keygen   <seed-hex32> <out-secret.key> <out-public.key>
 *   apg_sigtool sign     <secret.key> <pkg> <out.sig>
 *   apg_sigtool verify   <keyring-dir> <pkg> <sig>      (libAPG keyring_verify)
 *
 * keygen derives a deterministic keypair from a 32-byte seed so test vectors
 * are reproducible. sign uses the same streaming API libAPG's sign_file uses.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <sodium.h>
#include <apg/keyring.h>

static int hex2bin_checked(const char *hex, unsigned char *out, size_t outlen)
{
    if (strlen(hex) != outlen * 2)
        return -1;
    return sodium_hex2bin(out, outlen, hex, strlen(hex), NULL, NULL, NULL) == 0 ? 0 : -1;
}

static int cmd_keygen(const char *seedhex, const char *sk_path, const char *pk_path)
{
    unsigned char seed[crypto_sign_SEEDBYTES];
    if (hex2bin_checked(seedhex, seed, sizeof seed) != 0)
    {
        fprintf(stderr, "seed must be %zu hex bytes\n", (size_t)crypto_sign_SEEDBYTES);
        return 2;
    }
    unsigned char pk[crypto_sign_PUBLICKEYBYTES];
    unsigned char sk[crypto_sign_SECRETKEYBYTES];
    crypto_sign_seed_keypair(pk, sk, seed);

    FILE *f = fopen(sk_path, "wb");
    if (!f || fwrite(sk, 1, sizeof sk, f) != sizeof sk) { fprintf(stderr, "sk write\n"); return 2; }
    fclose(f);
    f = fopen(pk_path, "wb");
    if (!f || fwrite(pk, 1, sizeof pk, f) != sizeof pk) { fprintf(stderr, "pk write\n"); return 2; }
    fclose(f);
    return 0;
}

static int cmd_sign(const char *sk_path, const char *pkg, const char *sig_path)
{
    unsigned char sk[crypto_sign_SECRETKEYBYTES];
    FILE *f = fopen(sk_path, "rb");
    if (!f || fread(sk, 1, sizeof sk, f) != sizeof sk) { fprintf(stderr, "sk read\n"); return 2; }
    fclose(f);

    /* Identical to libAPG sign_file: stream the package through the
     * crypto_sign state, then final_create. This is Ed25519ph. */
    crypto_sign_state st;
    crypto_sign_init(&st);
    f = fopen(pkg, "rb");
    if (!f) { fprintf(stderr, "pkg open\n"); return 2; }
    unsigned char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0)
        crypto_sign_update(&st, buf, n);
    int rderr = ferror(f);
    fclose(f);
    if (rderr) { fprintf(stderr, "pkg read\n"); return 2; }

    unsigned char sig[crypto_sign_BYTES];
    unsigned long long siglen;
    if (crypto_sign_final_create(&st, sig, &siglen, sk) != 0) { fprintf(stderr, "sign\n"); return 2; }

    f = fopen(sig_path, "wb");
    if (!f || fwrite(sig, 1, siglen, f) != siglen) { fprintf(stderr, "sig write\n"); return 2; }
    fclose(f);
    return 0;
}

static int cmd_verify(const char *dir, const char *pkg, const char *sig)
{
    struct keyring *kr = keyring_load(dir);
    bool ok = kr ? keyring_verify(kr, pkg, sig) : false;
    printf("{\"keyring_loaded\":%s,\"verified\":%s}\n",
           kr ? "true" : "false", ok ? "true" : "false");
    keyring_free(kr);
    return 0;
}

int main(int argc, char **argv)
{
    if (sodium_init() < 0) { fprintf(stderr, "sodium_init\n"); return 2; }
    if (argc == 5 && !strcmp(argv[1], "keygen"))
        return cmd_keygen(argv[2], argv[3], argv[4]);
    if (argc == 5 && !strcmp(argv[1], "sign"))
        return cmd_sign(argv[2], argv[3], argv[4]);
    if (argc == 5 && !strcmp(argv[1], "verify"))
        return cmd_verify(argv[2], argv[3], argv[4]);
    fprintf(stderr,
            "usage:\n"
            "  apg_sigtool keygen <seed-hex32> <out-secret.key> <out-public.key>\n"
            "  apg_sigtool sign <secret.key> <pkg> <out.sig>\n"
            "  apg_sigtool verify <keyring-dir> <pkg> <sig>\n");
    return 2;
}
