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
[[maybe_unused]] constexpr size_t ANDROID_MINIMUM_PERFORMANCE_CORES = 6;

constexpr std::chrono::nanoseconds ANDROID_POLICY_POLL_INTERVAL = std::chrono::milliseconds{5000};

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

CpuTopologyState& State() {
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

[[maybe_unused]] bool MidrsAreDistinct(const std::vector<CoreInfo>& cores
