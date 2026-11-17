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
#   usr/include, usr/lib       the NDK sysroot headers and the API-level
#                              libraries/crt objects (x86_64 only for now;
#                              the aarch64 zip ships the binary and links)
#
# Driven from the ExternalProject install step in
# term/src/main/jni/CMakeLists.txt. Inputs: BUSYBOX_BIN, BUSYBOX_LINKS,
# STAGE_DIR, ENV_DIR, ANDROID_ABI, TRIPLE, API, NDK_SYSROOT, OUT_ZIP.

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
file(WRITE "${STAGE_DIR}/usr/EXECUTABLES.txt" "bin/busybox\n")

if(ANDROID_ABI STREQUAL "x86_64")
    file(COPY "${NDK_SYSROOT}/usr/include/" DESTINATION "${STAGE_DIR}/usr/include")
    file(COPY "${NDK_SYSROOT}/usr/lib/${TRIPLE}/${API}/" DESTINATION "${STAGE_DIR}/usr/lib")
    file(GLOB clang_rt "${NDK_SYSROOT}/usr/lib/clang/*/lib/linux/libclang_rt.builtins-*-android.a")
    if(clang_rt)
        file(COPY ${clang_rt} DESTINATION "${STAGE_DIR}/usr/lib")
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
