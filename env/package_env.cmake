# Packages the built BusyBox into the environment asset zip the app
# extracts into its files/ prefix on first launch:
#
#   usr/bin/busybox            the executable itself
#   usr/SYMLINKS.txt           applet symlinks, in the same format the
#                              bootstrap builder produces and
#                              Bootstrap.createSymlinks() consumes:
#                              one "target←link" pair per line, the link
#                              relative to the prefix
#   usr/EXECUTABLES.txt        files to mark executable after extraction
#   usr/share/licenses/busybox the GPL-2.0 license text
#   usr/include                the NDK sysroot headers (x86_64 only for
#                              now; the aarch64 zip ships the binaries
#                              and links)
#   usr/lib/<triple>/<api>     the NDK sysroot API-level libraries and
#                              crt objects, in the NDK's own layout.
#                              They are link-time stubs and must NOT sit
#                              directly in usr/lib: a binary whose
#                              library search path includes usr/lib
#                              would load the stub libc.so/libdl.so at
#                              runtime and crash. usr/lib itself holds
#                              only real runtime libraries.
#   usr/bin                    plus the tools from TOOLS_DIR (bash, the
#                              OpenSSH clients, clang), built from
#                              source by the other ExternalProjects
#
# Driven from the ExternalProject install step in
# term/src/main/jni/CMakeLists.txt. Inputs: BUSYBOX_BIN, BUSYBOX_LINKS,
# STAGE_DIR, ENV_DIR, ANDROID_ABI, TRIPLE, API, NDK_SYSROOT, TOOLS_DIR,
# DEPS_DIR, OUT_ZIP.

file(REMOVE_RECURSE "${STAGE_DIR}")
file(MAKE_DIRECTORY
    "${STAGE_DIR}/usr/bin"
    "${STAGE_DIR}/usr/lib"
    "${STAGE_DIR}/usr/include"
    "${STAGE_DIR}/usr/tmp"
    "${STAGE_DIR}/usr/share/licenses/busybox")

file(COPY "${BUSYBOX_BIN}" DESTINATION "${STAGE_DIR}/usr/bin"
    FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
                     GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
file(COPY "${ENV_DIR}/licenses/busybox-GPL-2.0.txt"
    DESTINATION "${STAGE_DIR}/usr/share/licenses/busybox")
if(EXISTS "${ENV_DIR}/licenses/bash-GPL-3.0.txt")
    file(MAKE_DIRECTORY "${STAGE_DIR}/usr/share/licenses/bash")
    file(COPY "${ENV_DIR}/licenses/bash-GPL-3.0.txt"
        DESTINATION "${STAGE_DIR}/usr/share/licenses/bash")
endif()

if(DEFINED TOOLS_DIR AND IS_DIRECTORY "${TOOLS_DIR}/bin")
    file(COPY "${TOOLS_DIR}/bin/" DESTINATION "${STAGE_DIR}/usr/bin"
        FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
                         GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
endif()

# The OpenSSH clients' runtime libraries, from the deps prefix. These
# are real shared libraries (not sysroot stubs), so they belong in
# usr/lib itself, where the clients' rpath points.
if(DEFINED DEPS_DIR)
    foreach(dep_lib libcrypto.so libssl.so)
        if(EXISTS "${DEPS_DIR}/lib/${dep_lib}")
            file(COPY "${DEPS_DIR}/lib/${dep_lib}" DESTINATION "${STAGE_DIR}/usr/lib"
                FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
                                 GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
        endif()
    endforeach()
    foreach(tool openssh openssl zlib)
        if(EXISTS "${ENV_DIR}/licenses/${tool}-LICENSE.txt")
            file(MAKE_DIRECTORY "${STAGE_DIR}/usr/share/licenses/${tool}")
            file(COPY "${ENV_DIR}/licenses/${tool}-LICENSE.txt"
                DESTINATION "${STAGE_DIR}/usr/share/licenses/${tool}")
        endif()
    endforeach()
    if(EXISTS "${ENV_DIR}/licenses/openssl-Apache-2.0.txt")
        file(MAKE_DIRECTORY "${STAGE_DIR}/usr/share/licenses/openssl")
        file(COPY "${ENV_DIR}/licenses/openssl-Apache-2.0.txt"
            DESTINATION "${STAGE_DIR}/usr/share/licenses/openssl")
    endif()
endif()

file(READ "${BUSYBOX_LINKS}" links_text)
string(REPLACE "\n" ";" link_lines "${links_text}")
set(symlinks "")
foreach(link_line IN LISTS link_lines)
    if(NOT link_line STREQUAL "")
        get_filename_component(applet "${link_line}" NAME)
        string(APPEND symlinks "busybox←bin/${applet}\n")
    endif()
endforeach()
file(WRITE "${STAGE_DIR}/usr/SYMLINKS.txt" "${symlinks}")

file(GLOB stage_programs "${STAGE_DIR}/usr/bin/*")
set(executables "")
foreach(program IN LISTS stage_programs)
    get_filename_component(program_name "${program}" NAME)
    string(APPEND executables "bin/${program_name}\n")
endforeach()
file(WRITE "${STAGE_DIR}/usr/EXECUTABLES.txt" "${executables}")

if(ANDROID_ABI STREQUAL "x86_64")
    file(COPY "${NDK_SYSROOT}/usr/include/" DESTINATION "${STAGE_DIR}/usr/include")
    file(COPY "${NDK_SYSROOT}/usr/lib/${TRIPLE}/${API}/" DESTINATION "${STAGE_DIR}/usr/lib/${TRIPLE}/${API}")
    file(GLOB clang_rt "${NDK_SYSROOT}/usr/lib/clang/*/lib/linux/libclang_rt.builtins-*-android.a")
    if(clang_rt)
        file(COPY ${clang_rt} DESTINATION "${STAGE_DIR}/usr/lib/${TRIPLE}/${API}")
    endif()
endif()

get_filename_component(out_dir "${OUT_ZIP}" DIRECTORY)
file(MAKE_DIRECTORY "${out_dir}")
execute_process(
    COMMAND ${CMAKE_COMMAND} -E tar cf "${OUT_ZIP}" --format=zip usr
    WORKING_DIRECTORY "${STAGE_DIR}"
    RESULT_VARIABLE tar_result)
if(NOT tar_result EQUAL 0)
    message(FATAL_ERROR "Failed to create environment asset zip ${OUT_ZIP}")
endif()
message(STATUS "BusyBox environment asset: ${OUT_ZIP}")
