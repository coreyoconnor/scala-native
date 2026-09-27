#if defined(SCALANATIVE_GC_IMMIX) || defined(SCALANATIVE_GC_COMMIX) ||         \
    defined(SCALANATIVE_GC_NONE) || defined(SCALANATIVE_GC_EXPERIMENTAL)

// Disable MSVC deprecation warnings for standard C functions
#ifdef _WIN32
#define _CRT_SECURE_NO_WARNINGS
#endif

#include "YieldPointTrap.h"
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <stdatomic.h>
#include "shared/MemoryMap.h"
#include "shared/MemoryInfo.h"
#include "shared/Log.h"
#include "shared/ThreadUtil.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else // Unix
#include <sys/mman.h>
#if defined(__FreeBSD__) && !defined(MAP_NORESERVE)
#define MAP_NORESERVE 0
#endif
#endif

/*
 * Recycled trap-page freelist.
 *
 * Every mutator thread owns a trap cell (a single page protected with
 * mprotect(PROT_NONE) when GC arms safepoints). The pointer to that cell is
 * cached in the thread-local @scalanative_GC_yieldpoint_trap slot.
 *
 * Continuation suspend/resume can move execution to a different OS thread
 * mid-function. LLVM is allowed to CSE/hoist the TLS-slot read (and the
 * underlying `mrs tpidr_el0` on AArch64) to a single read at function entry
 * and spill the resulting trap-cell pointer to the stack. After resumption on
 * another carrier the spilled pointer still references the *original*
 * carrier's trap cell. If that carrier has terminated and we munmap'd the
 * page, the next safepoint poll would dereference unmapped memory and
 * SIGSEGV with si_code=SEGV_MAPERR. The signal handler cannot recover because
 * there is no GC in progress (so isContinuationStaleTrapFault returns false).
 *
 * To keep stale TLS-cached pointers safe to dereference we never unmap a trap
 * page. Pages from terminated mutators are disarmed and pushed onto a global
 * freelist, then handed out to subsequent threads. The freelist link is stored
 * inside the page itself (the pointer slot is unused while disarmed).
 *
 * Worst-case scenarios after recycling:
 *   - Stale pointer reads a disarmed page: load returns whatever bits live in
 *     the page (the safepoint poll discards the loaded value via ldr xzr).
 *   - Stale pointer reads a page whose new owner has been re-armed during
 *     STW: SEGV_ACCERR (BUS_ADRERR on macOS) with Synchronizer_stopThreads
 *     set; SafepointTrapHandler treats it as a stale-carrier trap and yields
 *     on the current mutator.
 */
static safepoint_t YieldPointTrap_freeList = NULL;
static mutex_t YieldPointTrap_freeListLock;
static atomic_bool YieldPointTrap_freeListReady = false;

static void YieldPointTrap_initFreeList(void) {
    bool expected = false;
    if (atomic_compare_exchange_strong(&YieldPointTrap_freeListReady, &expected,
                                       true)) {
        mutex_init(&YieldPointTrap_freeListLock);
    } else {
        /* Wait for the initializer to publish the mutex. */
        while (!atomic_load(&YieldPointTrap_freeListReady)) {
            thread_yield();
        }
    }
}

static safepoint_t YieldPointTrap_popFreeList(void) {
    if (!atomic_load(&YieldPointTrap_freeListReady))
        return NULL;
    mutex_lock(&YieldPointTrap_freeListLock);
    safepoint_t reused = YieldPointTrap_freeList;
    if (reused != NULL) {
        YieldPointTrap_freeList = (safepoint_t) * (void **)reused;
        *(void **)reused = NULL;
    }
    mutex_unlock(&YieldPointTrap_freeListLock);
    return reused;
}

static void YieldPointTrap_pushFreeList(safepoint_t ref) {
    YieldPointTrap_initFreeList();
    mutex_lock(&YieldPointTrap_freeListLock);
    *(void **)ref = (void *)YieldPointTrap_freeList;
    YieldPointTrap_freeList = ref;
    mutex_unlock(&YieldPointTrap_freeListLock);
}

/*
 * Registry of every trap page ever allocated by this process.
 *
 * SafepointTrapHandler uses it to answer "is this fault address one of our own
 * safepoint pages" without consulting Synchronizer_stopThreads. That boolean is
 * racy against signal delivery: a stale trap from a migrated continuation (see
 * the file header above) can be *delivered* after the STW pause that armed the
 * page has ended and stopThreads has flipped back to false. The OS may delay
 * signal delivery arbitrarily, and nothing re-checks stopThreads at the instant
 * the fault occurred, only when the handler runs. When the race lands, a
 * structurally safe stale trap falls through the handler's checks and is treated
 * as a real SIGSEGV/SIGBUS, aborting the process.
 *
 * Page identity is not racy the way the boolean is. Trap pages are never
 * munmap'd/VirtualFree'd (see the file header above) and nothing else in this
 * process mprotects a page for this purpose, so "this address falls on a page
 * this registry recorded" is permanent from page creation onward, whether the
 * fault is handled promptly or arbitrarily late.
 *
 * Append-only and fixed capacity: a realloc would have to be signal-safe on the
 * reader side, which a growing allocation is not. One entry is needed per trap
 * page ever created, i.e. per mutator thread that has ever existed (pages are
 * recycled via the freelist above), not per live thread or GC cycle.
 */
#define YIELD_POINT_TRAP_REGISTRY_CAPACITY 65536
static void *YieldPointTrap_registry[YIELD_POINT_TRAP_REGISTRY_CAPACITY];
static atomic_size_t YieldPointTrap_registryCount = 0;

static void YieldPointTrap_register(void *addr) {
    // Writers run from ordinary thread context, not a signal handler, so they
    // may take a lock. Reusing the freelist's mutex serializes concurrent
    // registrations; only one writer touches count/the array at a time.
    // The reader (YieldPointTrap_isRegisteredPage, called from a signal
    // handler) cannot take this lock and does a lock-free acquire load of the
    // counter instead. The release store below guarantees that any thread whose
    // acquire load observes the bumped count also observes the pointer write
    // just before it. Without the release ordering, a reader on a weakly
    // ordered architecture could observe the bumped count before the pointer
    // write is visible and read a stale slot.
    YieldPointTrap_initFreeList();
    mutex_lock(&YieldPointTrap_freeListLock);
    size_t idx = atomic_load_explicit(&YieldPointTrap_registryCount,
                                      memory_order_relaxed);
    if (idx < YIELD_POINT_TRAP_REGISTRY_CAPACITY) {
        YieldPointTrap_registry[idx] = addr;
    }
    atomic_store_explicit(&YieldPointTrap_registryCount, idx + 1,
                          memory_order_release);
    mutex_unlock(&YieldPointTrap_freeListLock);
    // idx >= capacity drops the registration silently; that only makes one more
    // mutator's stale traps fall back to the pre-fix behaviour, not a new risk.
}

// Signal-handler-safe: no locks, no allocation. An acquire load of a monotonic
// counter plus a scan of already-published entries (see
// YieldPointTrap_register).
bool YieldPointTrap_isRegisteredPage(const void *faultAddress) {
    if (faultAddress == NULL)
        return false;
    uintptr_t pageSize = getPageSize();
    uintptr_t faultPage = ((uintptr_t)faultAddress) / pageSize;
    size_t count = atomic_load_explicit(&YieldPointTrap_registryCount,
                                        memory_order_acquire);
    if (count > YIELD_POINT_TRAP_REGISTRY_CAPACITY)
        count = YIELD_POINT_TRAP_REGISTRY_CAPACITY;
    for (size_t i = 0; i < count; i++) {
        void *page = YieldPointTrap_registry[i];
        if (page != NULL && ((uintptr_t)page) / pageSize == faultPage) {
            return true;
        }
    }
    return false;
}

#if !defined(__APPLE__)
void YieldPointTrap_resetTaskMachBadAccessPorts(void) {
    /* No-op on non-Apple. The Apple implementation lives in
     * YieldPointTrap_mach.c (kept separate to avoid a typedef collision
     * between <mach/mach_types.h> and shared/ThreadUtil.h). */
}
#endif

safepoint_t YieldPointTrap_init() {
    YieldPointTrap_initFreeList();
    safepoint_t reused = YieldPointTrap_popFreeList();
    if (reused != NULL) {
#if defined(__APPLE__)
        YieldPointTrap_resetTaskMachBadAccessPorts();
#endif
        return reused;
    }

    bool allocated;
    void *addr =
#ifdef _WIN32
        VirtualAlloc(NULL, sizeof(safepoint_t), MEM_RESERVE | MEM_COMMIT,
                     PAGE_READWRITE);
    allocated = addr != NULL;
#else
        mmap(NULL, sizeof(safepoint_t), PROT_WRITE | PROT_READ,
             MAP_NORESERVE | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    allocated = addr != MAP_FAILED;
#endif
    if (!allocated) {
        GC_LOG_ERROR("Failed to create GC safepoint trap: %s", strerror(errno));
        exit(errno);
    }
    // Only reached for a genuinely new page: the freelist-reuse path above
    // already returned, and a page recycled from the freelist was registered
    // when it was first created. The registry records every distinct page
    // address, not a reference count.
    YieldPointTrap_register(addr);

#if defined(__APPLE__)
    YieldPointTrap_resetTaskMachBadAccessPorts();
#endif
    return addr;
}

void YieldPointTrap_arm(safepoint_t ref) {
    bool success;
#ifdef _WIN32
    DWORD oldAccess;
    success = VirtualProtect((LPVOID)ref, sizeof(safepoint_t), PAGE_NOACCESS,
                             &oldAccess);
#else
    success = mprotect((void *)ref, sizeof(safepoint_t), PROT_NONE) == 0;
#endif
    if (!success) {
        GC_LOG_ERROR("Failed to enable GC collect trap: %s", strerror(errno));
        exit(errno);
    }
}

void YieldPointTrap_disarm(safepoint_t ref) {
    bool success;
#ifdef _WIN32
    DWORD oldAccess;
    success = VirtualProtect((LPVOID)ref, sizeof(safepoint_t), PAGE_READWRITE,
                             &oldAccess);
#else
    success =
        mprotect((void *)ref, sizeof(safepoint_t), PROT_WRITE | PROT_READ) == 0;
#endif
    if (!success) {
        GC_LOG_ERROR("Failed to disable GC collect trap: %s", strerror(errno));
        exit(errno);
    }
}

void YieldPointTrap_free(safepoint_t ref) {
    if (ref == NULL) {
        return;
    }
    /* Disarm before recycling so that any stale TLS-cached pointer can read
     * the page without faulting. The page is then handed back to the global
     * freelist; we deliberately never munmap/VirtualFree it. See the file
     * header for the full rationale (continuation migration + LLVM CSE of
     * the TLS trap-cell pointer). */
    YieldPointTrap_disarm(ref);
    *(void **)ref = NULL;
    YieldPointTrap_pushFreeList(ref);
}

#endif
