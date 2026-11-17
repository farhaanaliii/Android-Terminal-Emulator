/* Android shim: bionic has no getrrsetbyname and no BSD resolver state
 * to implement it with, so this replacement always reports failure.
 * Only VerifyHostKeyDNS (SSHFP record lookup) is affected; all other
 * ssh functionality is unchanged. */
#include "includes.h"

#include <stdlib.h>

#include "getrrsetbyname.h"

int
getrrsetbyname(const char *hostname, unsigned int rdclass, unsigned int rdtype,
    unsigned int flags, struct rrsetinfo **res)
{
	(void)hostname;
	(void)rdclass;
	(void)rdtype;
	(void)flags;
	*res = NULL;
	return (ERRSET_FAIL);
}

void
freerrset(struct rrsetinfo *rrset)
{
	(void)rrset;
}
