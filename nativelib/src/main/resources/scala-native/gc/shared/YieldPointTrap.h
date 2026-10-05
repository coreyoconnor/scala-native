#ifndef YieldPointTrap_H
#define YieldPointTrap_H

#include <stdbool.h>

typedef void **safepoint_t;
safepoint_t YieldPointTrap_init(void);
/** macOS: clear task EXC_BAD_ACCESS Mach exception ports so faults reach
 *  SIGBUS/SIGSEGV; no-op elsewhere. Safe to call before each GC suspend. */
void YieldPointTrap_resetTaskMachBadAccessPorts(void);
void YieldPointTrap_arm(safepoint_t ref);
void YieldPointTrap_disarm(safepoint_t ref);
void YieldPointTrap_free(safepoint_t ref);
/** Signal-handler-safe: true if `faultAddress` falls on a page this process
 *  ever created via YieldPointTrap_init (pages are permanent, see the
 *  registry comment in YieldPointTrap.c) -- a timing-independent way to
 *  recognize "this is one of our own safepoint traps" that doesn't depend
 *  on Synchronizer_stopThreads' value at the moment the signal happens to
 *  be handled. */
bool YieldPointTrap_isRegisteredPage(const void *faultAddress);

#endif
