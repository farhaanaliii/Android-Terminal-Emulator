# Source adjustments applied to the pristine OpenSSH tarball by the
# ExternalProject patch step. Two changes, both forced by bionic:
#
# 1. openbsd-compat/getrrsetbyname.c is replaced wholesale with
#    env/openssh_getrrsetbyname.c (see that file for why): bionic has
#    no BSD resolver state for the upstream implementation.
#
# 2. openbsd-compat/explicit_bzero.c calls bzero through a volatile
#    function pointer. Bionic's bzero is a macro (a static inline
#    wrapping memset), so its address cannot be taken and the file
#    does not compile. The indirection is rerouted through memset
#    with identical semantics.
#
# 3. misc.c tilde_expand() resolves "~" through the passwd entry.
#    Bionic's passwd entry for an app uid names /data as the home
#    directory, so ssh would look for keys and known_hosts in
#    /data/.ssh instead of the app's home. For the caller's own
#    tilde, $HOME (which the session sets to the real home) wins.
#
# 4. hostfile.c hostfile_replace_entries() backs the old known_hosts
#    up with link() before replacing it. Android blocks hardlink
#    creation for apps (EPERM/EACCES), which made every host-key
#    update fail noisily. On those two errors the backup is skipped;
#    the atomic temp-file-and-rename replace still happens.
#
# Inputs: SRC (the extracted OpenSSH tree), STUB (the replacement
# getrrsetbyname.c). Fails loudly if an expected upstream line is not
# found, so a future version bump cannot silently lose the patch.

file(COPY_FILE "${STUB}" "${SRC}/openbsd-compat/getrrsetbyname.c")

set(bzero_file "${SRC}/openbsd-compat/explicit_bzero.c")
file(READ "${bzero_file}" bzero_text)
string(REPLACE "static void (* volatile ssh_bzero)(void *, size_t) = bzero;"
    "static void *(* volatile ssh_memset)(void *, int, size_t) = memset;"
    bzero_text "${bzero_text}")
string(FIND "${bzero_text}" "ssh_memset" found)
if(found EQUAL -1)
    message(FATAL_ERROR "openssh_patch: bzero declaration not found in explicit_bzero.c")
endif()
string(REPLACE "ssh_bzero(p, n);" "ssh_memset(p, 0, n);" bzero_text "${bzero_text}")
string(FIND "${bzero_text}" "ssh_bzero" leftover)
if(NOT leftover EQUAL -1)
    message(FATAL_ERROR "openssh_patch: ssh_bzero call still present in explicit_bzero.c")
endif()
file(WRITE "${bzero_file}" "${bzero_text}")

set(misc_file "${SRC}/misc.c")
file(READ "${misc_file}" misc_text)
string(REPLACE "const char *path = NULL, *user = NULL;"
    "const char *path = NULL, *user = NULL, *homedir = NULL;"
    misc_text "${misc_text}")
string(REPLACE "slash = (len = strlen(pw->pw_dir)) == 0 || pw->pw_dir[len - 1] != '/';"
    "if (user == NULL && uid == getuid()) homedir = getenv(\"HOME\"); if (homedir == NULL || *homedir == '\\0') homedir = pw->pw_dir; slash = (len = strlen(homedir)) == 0 || homedir[len - 1] != '/';"
    misc_text "${misc_text}")
string(REPLACE "if ((r = xasprintf(&s, \"%s%s%s\", pw->pw_dir,"
    "if ((r = xasprintf(&s, \"%s%s%s\", homedir,"
    misc_text "${misc_text}")
string(FIND "${misc_text}" "homedir = getenv" applied)
if(applied EQUAL -1)
    message(FATAL_ERROR "openssh_patch: tilde_expand HOME fix not applied to misc.c")
endif()
string(FIND "${misc_text}" "xasprintf(&s, \"%s%s%s\", pw->pw_dir" leftover)
if(NOT leftover EQUAL -1)
    message(FATAL_ERROR "openssh_patch: tilde_expand still uses pw_dir in misc.c")
endif()
file(WRITE "${misc_file}" "${misc_text}")

set(hostfile "${SRC}/hostfile.c")
file(READ "${hostfile}" hostfile_text)
string(REPLACE "if (link(filename, back) == -1) {"
    "if (link(filename, back) == -1 && errno != EPERM && errno != EACCES) {"
    hostfile_text "${hostfile_text}")
string(FIND "${hostfile_text}" "errno != EPERM" applied)
if(applied EQUAL -1)
    message(FATAL_ERROR "openssh_patch: hostfile link fallback not applied")
endif()
file(WRITE "${hostfile}" "${hostfile_text}")
