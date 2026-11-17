# Post-configure fix for OpenSSH, run between the configure and build
# steps (ExternalProject step fix_config_h).
#
# OpenSSH's configure probes for the __sentinel__ function attribute
# by declaring a function with an empty __sentinel__ in the test
# program. On Android the probe still fails (bionic headers), so
# configure leaves HAVE_ATTRIBUTE__SENTINEL__ undefined and OpenSSH
# substitutes an empty fallback macro. Bionic's own headers use
# __sentinel__(1), which the empty macro then breaks. Clang accepts
# the attribute fine, so define it directly.
#
# Input: BINARY_DIR (the OpenSSH build tree holding config.h).

set(config_h "${BINARY_DIR}/config.h")
file(READ "${config_h}" config_text)
string(REPLACE "/* #undef HAVE_ATTRIBUTE__SENTINEL__ */"
    "#define HAVE_ATTRIBUTE__SENTINEL__ 1" config_text "${config_text}")
string(FIND "${config_text}" "#define HAVE_ATTRIBUTE__SENTINEL__ 1" found)
if(found EQUAL -1)
    message(FATAL_ERROR "openssh_config_fix: sentinel undef line not found in config.h")
endif()
file(WRITE "${config_h}" "${config_text}")
