// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2013 Dolphin Emulator Project
// SPDX-FileCopyrightText: 2014 Citra Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <chrono>
#include <limits>
#include <string>
#include <thread>

#include "common/adpf.h"
#include "common/error.h"
#include "common/logging.h"
#include "common/assert.h"
#include "common/thread.h"
#ifdef __APPLE__
#include <mach/mach.h>
#elif defined(__HAIKU__)
#include <kernel/OS.h>
#elif defined(_WIN32)
#include <windows.h>
#include "common/string_util.h"
#include "common/windows/timer_resolution.h"
#else
#if defined(__FreeBSD__)
#include <sys/cpuset.h>
#include <sys/_cpuset.h>
#include <pthread_np.h>
// Compatibility with CPUset
#define cpu_set_t cpuset_t
#elif defined(__DragonFly__) || defined(__OpenBSD__) || defined(__Bitrig__)
#include <pthread_np.h>
#endif
#include <pthread.h>
#include <sched.h>
#endif

#ifndef _WIN32
#include <unistd.h>
#endif

#if defined(__linux__) || defined(__ANDROID__)
#include <sys/resource.h>
#include <algorithm>

namespace {
constexpr int NICE_AUDIO = -16;
constexpr int NICE_URGENT_DISPLAY = -8;
constexpr int NICE_DISPLAY = -4;
constexpr int NICE_DEFAULT = 0;
constexpr int NICE_BACKGROUND = 10;

int LowestAllowedNice() {
    static const int lowest = [] {
        rlimit limit{};
        if (getrlimit(RLIMIT_NICE, &limit) != 0) {
            return 0;
        }
        if (limit.rlim_cur >= 40) {
            return -20;
        }
        return 20 - static_cast<int>(limit.rlim_cur);
    }();
    return lowest;
}

int NiceValueForPriority(Common::ThreadPriority priority) {
    const int wanted = [priority] {
        switch (priority) {
        case Common::ThreadPriority::Low: return NICE_BACKGROUND;
        case Common::ThreadPriority::Normal: return NICE_DEFAULT;
        case Common::ThreadPriority::High: return NICE_DISPLAY;
        case Common::ThreadPriority::VeryHigh: return NICE_URGENT_DISPLAY;
        case Common::ThreadPriority::Critical: return NICE_AUDIO;
        default: return NICE_DEFAULT;
        }
    }();
    return (std::max)(wanted, (std::min)(NICE_DEFAULT, LowestAllowedNice()));
}
} // Anonymous namespace
#endif

#ifdef __ANDROID__
#include <sys/utsname.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <vector>

namespace {
constexpr size_t ANDROID_MINIMUM_PERFORMANCE_CORES = 6;
constexpr s64 ANDROID_POLICY_POLL_INTERVAL_NS = 5000000000LL;

enum class CoreGroup {
    Unrestricted,
    Performance,
    Efficiency,
    Prime,
};

struct ThreadPolicy {
    pid_t tid;
    CoreGroup group;
    s32 nice_value;
    bool has_nice;
};

struct CoreInfo {
    s64 weight;
    u64 midr;
    s32 cpu;
};

struct CpuTopologyState {
    std::mutex topology_mutex;
    cpu_set_t allowed{};
    cpu_set_t prime{};
    cpu_set_t performance{};
    cpu_set_t efficiency{};
    bool separated = false;
    bool has_prime = false;
    bool initialized = false;

    pid_t canary_tid = 0;
    cpu_set_t canary_mask{};
    bool canary_valid = false;

    std::atomic<s64> next_poll_ns{0};

    std::mutex policy_mutex;
    std::vector<ThreadPolicy> policies;
};

inline CpuTopologyState& State() {
    static CpuTopologyState* const state = new CpuTopologyState();
    return *state;
}

struct PolicyRegistration {
    ~PolicyRegistration() {
        const pid_t tid = gettid();
        ::Common::ADPF::RemoveCurrentThread();
        CpuTopologyState& state = State();
        std::scoped_lock lock{state.policy_mutex};
        std::erase_if(state.policies,
                      [tid](const ThreadPolicy& policy) { return policy.tid == tid; });
    }
};

thread_local PolicyRegistration t_policy_registration;

s32 PossibleCpuCount() {
    std::ifstream file("/sys/devices/system/cpu/possible");
    std::string list;
    if (file && std::getline(file, list) && !list.empty()) {
        s64 highest = -1;
        const char* cursor = list.c_str();
        while (*cursor != '\0') {
            char* end = nullptr;
            const s64 value = std::strtol(cursor, &end, 10);
            if (end == cursor) {
                break;
            }
            highest = (std::max)(highest, value);
            cursor = end;
            while (*cursor == '-' || *cursor == ',') {
                ++cursor;
            }
        }
        if (highest >= 0) {
            return static_cast<s32>((std::min<s64>)(highest + 1, CPU_SETSIZE));
        }
    }
    const s64 configured = sysconf(_SC_NPROCESSORS_CONF);
    if (configured > 0) {
        return static_cast<s32>((std::min<s64>)(configured, CPU_SETSIZE));
    }
    return static_cast<s32>((std::min<s64>)(std::thread::hardware_concurrency(), CPU_SETSIZE));
}

s64 ReadCpuScalar(s32 cpu, const char* node) {
    s64 value = 0;
    std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/" + node);
    if (!file || !(file >> value) || value <= 0) {
        return 0;
    }
    return value;
}

u64 ReadCpuMidr(s32 cpu) {
    u64 midr = 0;
    std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(cpu) +
                       "/regs/identification/midr_el1");
    if (!file || !(file >> std::hex >> midr)) {
        return 0;
    }
    return midr;
}

std::vector<CoreInfo> CollectCores(const cpu_set_t& allowed, s32 total, const char* node,
                                   bool require_all) {
    std::vector<CoreInfo> cores;
    for (s32 cpu = 0; cpu < total; ++cpu) {
        if (!CPU_ISSET(cpu, &allowed)) {
            continue;
        }
        const s64 weight = ReadCpuScalar(cpu, node);
        if (weight <= 0) {
            if (require_all) {
                return {};
            }
            continue;
        }
        cores.push_back(CoreInfo{weight, ReadCpuMidr(cpu), cpu});
    }
    return cores;
}

[[maybe_unused]] bool WeightsAreUniform(const std::vector<CoreInfo>& cores) {
    if (cores.empty()) {
        return true;
    }
    const s64 first_weight = cores.front().weight;
    return std::all_of(cores.begin(), cores.end(),
                       [first_weight](const CoreInfo& core) { return core.weight == first_weight; });
}

[[maybe_unused]] bool MidrsAreDistinct(const std::vector<CoreInfo>& cores) {
    if (cores.empty()) {
        return false;
    }
    const u64 first_midr = cores.front().midr;
    if (first_midr == 0) {
        return false;
    }
    return std::any_of(cores.begin(), cores.end(),
                       [first_midr](const CoreInfo& core) { return core.midr != 0 && core.midr != first_midr; });
}

void ComputeTopologyLocked(CpuTopologyState& state) {
    state.initialized = true;
    state.separated = false;
    state.has_prime = false;
    CPU_ZERO(&state.allowed);
    CPU_ZERO(&state.prime);
    CPU_ZERO(&state.performance);
    CPU_ZERO(&state.efficiency);

    if (sched_getaffinity(getpid(), sizeof(state.allowed), &state.allowed) != 0) {
        return;
    }

    const s32 total = PossibleCpuCount();
    auto cores = CollectCores(state.allowed, total, "cpufreq/cpuinfo_max_freq", false);
    if (cores.empty()) {
        cores = CollectCores(state.allowed, total, "cpu_capacity", true);
    }
    if (cores.empty()) {
        return;
    }

    std::sort(cores.begin(), cores.end(), [](const CoreInfo& lhs, const CoreInfo& rhs) {
        if (lhs.weight != rhs.weight) {
            return lhs.weight > rhs.weight;
        }
        return lhs.cpu > rhs.cpu;
    });

    const s64 max_weight = cores.front().weight;

    for (const auto& core : cores) {
        if (core.weight >= (max_weight * 95) / 100) {
            CPU_SET(static_cast<size_t>(core.cpu), &state.prime);
        }
    }
    state.has_prime = CPU_COUNT(&state.prime) > 0;

    for (const auto& core : cores) {
        if (!CPU_ISSET(static_cast<size_t>(core.cpu), &state.prime) && core.weight >= (max_weight * 65) / 100) {
            CPU_SET(static_cast<size_t>(core.cpu), &state.performance);
        }
    }

    if (CPU_COUNT(&state.performance) == 0) {
        state.performance = state.allowed;
    }

    for (s32 cpu = 0; cpu < total; ++cpu) {
        const size_t c = static_cast<size_t>(cpu);
        if (CPU_ISSET(c, &state.allowed) &&
            !CPU_ISSET(c, &state.prime) &&
            !CPU_ISSET(c, &state.performance)) {
            CPU_SET(c, &state.efficiency);
        }
    }

    if (CPU_COUNT(&state.efficiency) == 0) {
        for (s32 cpu = 0; cpu < 4 && cpu < total; ++cpu) {
            const size_t c = static_cast<size_t>(cpu);
            if (CPU_ISSET(c, &state.allowed) && !CPU_ISSET(c, &state.prime)) {
                CPU_SET(c, &state.efficiency);
            }
        }
    }

    state.separated = CPU_COUNT(&state.efficiency) > 0 || state.has_prime;
}

void EnsureTopologyLocked(CpuTopologyState& state) {
    if (!state.initialized) {
        ComputeTopologyLocked(state);
    }
}

void RefreshTopologyLocked(CpuTopologyState& state) {
    if (!state.initialized) {
        ComputeTopologyLocked(state);
        return;
    }
    cpu_set_t current;
    CPU_ZERO(&current);
    if (sched_getaffinity(getpid(), sizeof(current), &current) != 0) {
        return;
    }
    if (std::memcmp(&current, &state.allowed, sizeof(current)) != 0) {
        ComputeTopologyLocked(state);
    }
}

bool ApplyCoreGroupLocked(CpuTopologyState& state, pid_t tid, CoreGroup group,
                          bool* gone = nullptr) {
    const cpu_set_t* mask = &state.allowed;
    if (group == CoreGroup::Prime && state.has_prime) {
        mask = &state.prime;
    } else if (group == CoreGroup::Performance && CPU_COUNT(&state.performance) > 0) {
        mask = &state.performance;
    } else if (group == CoreGroup::Efficiency && CPU_COUNT(&state.efficiency) > 0) {
        mask = &state.efficiency;
    }

    if (CPU_COUNT(mask) == 0) {
        return false;
    }
    if (sched_setaffinity(tid, sizeof(*mask), mask) != 0) {
        if (gone != nullptr && errno == ESRCH) {
            *gone = true;
            return false;
        }
        return false;
    }
    return true;
}

bool KernelPreservesRequestedAffinity() {
    utsname info{};
    if (uname(&info) != 0) {
        return false;
    }
    s32 major = 0;
    s32 minor = 0;
    if (std::sscanf(info.release, "%d.%d", &major, &minor) != 2) {
        return false;
    }
    return major > 6 || (major == 6 && minor >= 2);
}

void SnapshotCanaryLocked(CpuTopologyState& state) {
    state.canary_valid = false;
    if (!state.separated) {
        return;
    }
    for (const auto& policy : state.policies) {
        if (policy.group == CoreGroup::Unrestricted) {
            continue;
        }
        cpu_set_t mask;
        CPU_ZERO(&mask);
        if (sched_getaffinity(policy.tid, sizeof(mask), &mask) != 0) {
            continue;
        }
        state.canary_tid = policy.tid;
        state.canary_mask = mask;
        state.canary_valid = true;
        return;
    }
}

bool DueForPoll(CpuTopologyState& state) {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const s64 now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    s64 next = state.next_poll_ns.load(std::memory_order_relaxed);
    if (now_ns < next) {
        return false;
    }
    return state.next_poll_ns.compare_exchange_strong(
        next, now_ns + ANDROID_POLICY_POLL_INTERVAL_NS, std::memory_order_relaxed);
}

ThreadPolicy& AcquirePolicyLocked(CpuTopologyState& state, pid_t tid) {
    for (auto& policy : state.policies) {
        if (policy.tid == tid) {
            return policy;
        }
    }
    return state.policies.emplace_back(ThreadPolicy{tid, CoreGroup::Unrestricted, 0, false});
}

void SetCurrentThreadCoreGroup(CoreGroup group) {
    const pid_t tid = gettid();
    (void)&t_policy_registration;

    CoreGroup effective = group;
    if (tid == getpid() && group != CoreGroup::Unrestricted) {
        effective = CoreGroup::Unrestricted;
    }

    CpuTopologyState& state = State();
    std::scoped_lock topology_lock{state.topology_mutex};
    EnsureTopologyLocked(state);
    ApplyCoreGroupLocked(state, tid, effective);

    std::scoped_lock policy_lock{state.policy_mutex};
    AcquirePolicyLocked(state, tid).group = effective;
    if (!state.canary_valid) {
        SnapshotCanaryLocked(state);
    }
}

void RememberCurrentThreadNice(pid_t tid, s32 nice_value) {
    (void)&t_policy_registration;
    CpuTopologyState& state = State();
    std::scoped_lock lock{state.policy_mutex};
    ThreadPolicy& policy = AcquirePolicyLocked(state, tid);
    policy.nice_value = nice_value;
    policy.has_nice = true;
}
} // Anonymous namespace
#endif

#include "common/cpu_features.h"
#ifdef ARCHITECTURE_x86_64
#ifdef _MSC_VER
#include <intrin.h>
#else
#include <x86intrin.h>
#endif
#include "common/x64/rdtsc.h"
#endif

namespace Common {

void SetCurrentThreadPriority(ThreadPriority new_priority) {
#ifdef _WIN32
    int windows_priority = [&]() {
        switch (new_priority) {
        case ThreadPriority::Low: return THREAD_PRIORITY_BELOW_NORMAL;
        case ThreadPriority::Normal: return THREAD_PRIORITY_NORMAL;
        case ThreadPriority::High: return THREAD_PRIORITY_ABOVE_NORMAL;
        case ThreadPriority::VeryHigh: return THREAD_PRIORITY_HIGHEST;
        case ThreadPriority::Critical: return THREAD_PRIORITY_TIME_CRITICAL;
        default: return THREAD_PRIORITY_NORMAL;
        }
    }();
    SetThreadPriority(GetCurrentThread(), windows_priority);
#elif defined(__HAIKU__)
    int priority = [&]() {
        switch (new_priority) {
        case ThreadPriority::Low: return B_LOW_PRIORITY;
        case ThreadPriority::Normal: return B_NORMAL_PRIORITY;
        case ThreadPriority::High: return B_DISPLAY_PRIORITY;
        case ThreadPriority::VeryHigh: return B_URGENT_DISPLAY_PRIORITY;
        case ThreadPriority::Critical: return B_URGENT_PRIORITY;
        default: return B_NORMAL_PRIORITY;
        }
    }();
    set_thread_priority(find_thread(NULL), priority);
#elif defined(__ANDROID__)
    const int nice_value = NiceValueForPriority(new_priority);
    const pid_t tid = gettid();
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), nice_value) == 0) {
        RememberCurrentThreadNice(tid, nice_value);
    }

    if (new_priority == ThreadPriority::VeryHigh || new_priority == ThreadPriority::Critical) {
        SetCurrentThreadCoreGroup(CoreGroup::Prime);
    } else if
