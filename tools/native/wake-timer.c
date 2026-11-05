/*
 * Suspend-capable wait for the Root task watchdog.
 *
 * Interface: wake-timer DELAY_SECONDS STATE_DIRECTORY MODULE_DIRECTORY EXPECTED_NEXT
 *                      [EXPECTED_PARENT_PID ABSOLUTE_DEADLINE]
 * DELAY_SECONDS is 1..900; EXPECTED_NEXT is the scheduler's last next-run-at value.
 *
 * Uses only Linux syscalls, so the shipped static arm64 ELF needs neither Termux
 * nor a C runtime on the device. It holds no wake lock during the alarm wait.
 * REAMICRO_TIMER_TEST substitutes ordinary clocks and fixture files for /sys.
 */
#include <stdint.h>
#include <stddef.h>
#include <fcntl.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <sys/inotify.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>
#include <time.h>

#if defined(__aarch64__)
static long call6(long nr, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2),
                     "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
#elif defined(__x86_64__)
static long call6(long nr, long a, long b, long c, long d, long e, long f) {
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    long result;
    __asm__ volatile("syscall" : "=a"(result) : "a"(nr), "D"(a), "S"(b),
                     "d"(c), "r"(r10), "r"(r8), "r"(r9) : "rcx", "r11", "memory");
    return result;
}
#else
#error Build the production helper for arm64; regression builds also support x86_64.
#endif

#define SC0(n) call6(__NR_##n, 0, 0, 0, 0, 0, 0)
#define SC1(n,a) call6(__NR_##n, (long)(a), 0, 0, 0, 0, 0)
#define SC2(n,a,b) call6(__NR_##n, (long)(a), (long)(b), 0, 0, 0, 0)
#define SC3(n,a,b,c) call6(__NR_##n, (long)(a), (long)(b), (long)(c), 0, 0, 0)
#define SC4(n,a,b,c,d) call6(__NR_##n, (long)(a), (long)(b), (long)(c), (long)(d), 0, 0)
#define CLOSE(fd) do { if ((fd) >= 0) SC1(close, (fd)); } while (0)

enum { MAX_DELAY = 900, LOCK_SECONDS = 300 };
static int state_fd = -1, module_fd = -1, notify_fd = -1, alarm_fd = -1;
static int signal_fd = -1, clock_fd = -1, state_watch = -1, module_watch = -1;
static long owner_pid, parent_pid;
static const char *state_path, *module_path;
static char pid_text[32];
static int published, armed;

static size_t length(const char *s) {
    size_t n = 0;
    while (s[n]) ++n;
    return n;
}
static int same(const char *a, const char *b) {
    while (*a && *a == *b) { ++a; ++b; }
    return *a == *b;
}
static void copy(char *to, const char *from) {
    while ((*to++ = *from++)) {}
}
static char *number(char *out, uint64_t value) {
    char reversed[24];
    size_t n = 0;
    do { reversed[n++] = (char)('0' + value % 10); value /= 10; } while (value);
    while (n) *out++ = reversed[--n];
    *out = 0;
    return out;
}
static int decimal(const char *s, uint64_t *value) {
    uint64_t n = 0;
    if (!*s) return 0;
    for (; *s; ++s) {
        if (*s < '0' || *s > '9' || n > (UINT64_MAX - 9) / 10) return 0;
        n = n * 10 + (unsigned)(*s - '0');
    }
    *value = n;
    return 1;
}
static long write_all(int fd, const char *text, size_t size) {
    while (size) {
        long n = SC3(write, fd, text, size);
        if (n == -EINTR) continue;
        if (n <= 0) return n ? n : -EIO;
        text += n;
        size -= (size_t)n;
    }
    return 0;
}
static int write_at(int dir, const char *name, const char *text) {
    char temporary[96];
    copy(temporary, ".wake-timer-");
    copy(temporary + length(temporary), pid_text);
    copy(temporary + length(temporary), "-");
    copy(temporary + length(temporary), name);
    int fd = (int)SC4(openat, dir, temporary,
                     O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return fd;
    long result = write_all(fd, text, length(text));
    CLOSE(fd);
    if (!result) result = SC4(renameat, dir, temporary, dir, name);
    if (result) SC3(unlinkat, dir, temporary, 0);
    return (int)result;
}
static int read_at(int dir, const char *name, char *text, size_t size) {
    int fd = (int)SC4(openat, dir, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0);
    if (fd < 0) return fd;
    long n = SC3(read, fd, text, size - 1);
    CLOSE(fd);
    if (n < 0) return (int)n;
    text[n] = 0;
    while (n && (text[n - 1] == '\n' || text[n - 1] == '\r' || text[n - 1] == ' '))
        text[--n] = 0;
    return 0;
}
static int exists_at(int dir, const char *name) {
    struct stat value;
    return SC4(newfstatat, dir, name, &value, AT_SYMLINK_NOFOLLOW) == 0;
}
static int get_config(struct stat *value) {
    return (int)SC4(newfstatat, state_fd, "state.json", value, AT_SYMLINK_NOFOLLOW);
}
static int changed_config(const struct stat *a, const struct stat *b) {
    return a->st_ino != b->st_ino || a->st_dev != b->st_dev || a->st_size != b->st_size ||
           a->st_mtim.tv_sec != b->st_mtim.tv_sec || a->st_mtim.tv_nsec != b->st_mtim.tv_nsec ||
           a->st_ctim.tv_sec != b->st_ctim.tv_sec || a->st_ctim.tv_nsec != b->st_ctim.tv_nsec;
}
static int active(void) {
    return SC0(getppid) == parent_pid && !exists_at(state_fd, "stop") &&
           !exists_at(module_fd, "disable") && !exists_at(module_fd, "remove") &&
           exists_at(state_fd, "state.json");
}
static uint64_t next_value(void) {
    char text[32];
    uint64_t result = 0;
    if (read_at(state_fd, "next-run-at", text, sizeof text) < 0 || !decimal(text, &result))
        return 0;
    return result;
}
static int lock_file(int release) {
#ifdef REAMICRO_TIMER_TEST
    int fd = (int)SC4(openat, state_fd, release ? "test-wake-unlock" : "test-wake-lock",
                     O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
#else
    int fd = (int)SC4(openat, AT_FDCWD, release ? "/sys/power/wake_unlock" : "/sys/power/wake_lock",
                     O_WRONLY | O_CLOEXEC | O_NOFOLLOW, 0);
#endif
    if (fd < 0) return fd;
    const char *text = release ? "reamicro_tasks\n" : "reamicro_tasks 300000000000\n";
    long result = write_all(fd, text, length(text));
    CLOSE(fd);
    return (int)result;
}
static void error(const char *operation, long result) {
    char text[192], *end;
    copy(text, "wake-timer: ");
    copy(text + length(text), operation);
    copy(text + length(text), " errno=");
    end = number(text + length(text), result < 0 ? (uint64_t)-result : (uint64_t)result);
    copy(end, "\n");
    write_all(2, text, length(text));
}
static void remove_owned_status(void) {
    char current[32];
    if (published && read_at(state_fd, "wake.pid", current, sizeof current) == 0 &&
        same(current, pid_text)) {
        SC3(unlinkat, state_fd, "wake.pid", 0);
        SC3(unlinkat, state_fd, "wake-at", 0);
    }
}
static __attribute__((noreturn)) void finish(int status, int handoff) {
    /* Renew before dropping the alarm FD, including config-change handoffs. */
    if (handoff && lock_file(0) < 0 && armed) {
        error("handoff wake lock unavailable", -EACCES);
        status = status ? status : 4;
    }
    remove_owned_status();
    CLOSE(alarm_fd);
    CLOSE(clock_fd);
    CLOSE(signal_fd);
    CLOSE(notify_fd);
    CLOSE(module_fd);
    CLOSE(state_fd);
    SC1(exit_group, status);
    for (;;) {}
}
static int add_watch(int fd, uint32_t mask) {
    char path[64];
    copy(path, "/proc/self/fd/");
    number(path + length(path), (uint64_t)fd);
    return (int)SC3(inotify_add_watch, notify_fd, path, mask);
}
/* Return 1 for a scheduler/config event, 2 for directory invalidation. */
static int drain_events(void) {
    union { uint64_t alignment; char data[8192]; } buffer;
    int change = 0;
    for (;;) {
        long n = SC3(read, notify_fd, buffer.data, sizeof buffer.data);
        if (n == -EINTR) continue;
        if (n == -EAGAIN) return change;
        if (n <= 0) return 2;
        for (size_t offset = 0; offset + sizeof(struct inotify_event) <= (size_t)n;) {
            const struct inotify_event *event = (const void *)(buffer.data + offset);
            size_t entry = sizeof *event + event->len;
            if (entry > (size_t)n - offset) return 2;
            if (event->mask & (IN_Q_OVERFLOW | IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED))
                change = 2;
            if (event->len && event->wd == state_watch &&
                (same(event->name, "state.json") || same(event->name, "next-run-at") ||
                 same(event->name, "stop")))
                change = change ? change : 1;
            if (event->len && event->wd == module_watch &&
                (same(event->name, "disable") || same(event->name, "remove")))
                change = change ? change : 1;
#ifdef REAMICRO_TIMER_TEST
            if (event->len && same(event->name, "test-clock-change"))
                change = change ? change : 1;
#endif
            offset += entry;
        }
    }
}
static int create_alarm(clockid_t clock) {
#ifdef REAMICRO_TEST_NO_ALARM
    (void)clock;
    return -EPERM;
#else
#ifdef REAMICRO_TEST_BOOTTIME
    if (clock == CLOCK_REALTIME_ALARM) return -EINVAL;
#endif
#ifdef REAMICRO_TIMER_TEST
    clock = clock == CLOCK_REALTIME_ALARM ? CLOCK_REALTIME : CLOCK_BOOTTIME;
#endif
    return (int)SC2(timerfd_create, clock, TFD_CLOEXEC | TFD_NONBLOCK);
#endif
}
static int arm_timer(int fd, clockid_t clock, uint64_t delay, uint64_t absolute, int cancel_on_set,
                     uint64_t *deadline) {
    struct timespec now;
#ifdef REAMICRO_TIMER_TEST
    if (clock == CLOCK_REALTIME_ALARM) clock = CLOCK_REALTIME;
    if (clock == CLOCK_BOOTTIME_ALARM) clock = CLOCK_BOOTTIME;
#endif
    long result = SC2(clock_gettime, clock, &now);
    if (result < 0) return (int)result;
    struct itimerspec timer = {{0, 0}, {now.tv_sec + (time_t)delay, now.tv_nsec}};
    if (absolute && clock == CLOCK_REALTIME) {
        timer.it_value.tv_sec = (time_t)absolute;
        timer.it_value.tv_nsec = 0;
    }
    if (absolute && clock == CLOCK_REALTIME_ALARM) {
        timer.it_value.tv_sec = (time_t)absolute;
        timer.it_value.tv_nsec = 0;
    }
    if (absolute && (clock == CLOCK_BOOTTIME || clock == CLOCK_BOOTTIME_ALARM)) {
        struct timespec real;
        result = SC2(clock_gettime, CLOCK_REALTIME, &real);
        if (result < 0) return (int)result;
        int64_t seconds = (int64_t)absolute - real.tv_sec;
        timer.it_value.tv_sec = now.tv_sec + seconds;
        timer.it_value.tv_nsec = now.tv_nsec - real.tv_nsec;
        if (timer.it_value.tv_nsec < 0) {
            --timer.it_value.tv_sec;
            timer.it_value.tv_nsec += 1000000000;
        }
        /* An already-due scheduler deadline must hand off rather than disarm. */
        if (seconds <= 0) { timer.it_value.tv_sec = now.tv_sec; timer.it_value.tv_nsec = now.tv_nsec; }
    }
    int flags = TFD_TIMER_ABSTIME | (cancel_on_set ? TFD_TIMER_CANCEL_ON_SET : 0);
    result = SC4(timerfd_settime, fd, flags, &timer, 0);
    if (result == -EINVAL && cancel_on_set)
        result = SC4(timerfd_settime, fd, TFD_TIMER_ABSTIME, &timer, 0);
    if (deadline) *deadline = (uint64_t)timer.it_value.tv_sec;
    return (int)result;
}

static __attribute__((used, noreturn)) void wake_entry(long *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)(stack + 1);
    uint64_t delay, expected_next, target, absolute = 0, expected_parent = 0;
    if ((argc != 5 && argc != 7) || !decimal(argv[1], &delay) || delay < 1 || delay > MAX_DELAY ||
        !decimal(argv[4], &expected_next) || argv[2][0] != '/' || argv[3][0] != '/') {
        error("usage: DELAY(1..900) STATE_DIR MODULE_DIR EXPECTED_NEXT", -EINVAL);
        finish(2, 0);
    }
    if (argc == 7 && (!decimal(argv[5], &expected_parent) || expected_parent <= 1 ||
                      !decimal(argv[6], &absolute) || absolute == 0 || absolute > INT64_MAX - MAX_DELAY)) {
        error("invalid expected parent/absolute deadline", -EINVAL);
        finish(2, 0);
    }
    state_path = argv[2];
    module_path = argv[3];
    owner_pid = SC0(getpid);
    parent_pid = SC0(getppid);
    number(pid_text, (uint64_t)owner_pid);
    if (parent_pid <= 1 || (expected_parent && expected_parent != (uint64_t)parent_pid))
        finish(0, 0);

    /* Block before PR_SET_PDEATHSIG; recheck closes the fork/prctl race. */
    uint64_t mask = (UINT64_C(1) << (SIGTERM - 1)) | (UINT64_C(1) << (SIGINT - 1)) |
                    (UINT64_C(1) << (SIGHUP - 1));
    long result = SC4(rt_sigprocmask, SIG_BLOCK, &mask, 0, sizeof mask);
    if (result >= 0) result = SC2(prctl, PR_SET_PDEATHSIG, SIGTERM);
    if (result < 0 || SC0(getppid) != parent_pid) {
        error("parent-death signal setup", result);
        finish(3, 0);
    }
    signal_fd = (int)SC4(signalfd4, -1, &mask, sizeof mask, SFD_CLOEXEC | SFD_NONBLOCK);
    state_fd = (int)SC4(openat, AT_FDCWD, state_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW, 0);
    module_fd = (int)SC4(openat, AT_FDCWD, module_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW, 0);
    if (signal_fd < 0 || state_fd < 0 || module_fd < 0) {
        error("open private directories/signalfd", -EACCES);
        finish(3, 0);
    }
    struct stat before, after;
    if (!active()) finish(0, 0);
    if (get_config(&before) < 0) finish(3, 0);
    notify_fd = (int)SC1(inotify_init1, IN_CLOEXEC | IN_NONBLOCK);
    if (notify_fd < 0) { error("inotify_init", notify_fd); finish(3, 0); }
    uint32_t watch_mask = IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_DELETE |
                          IN_DELETE_SELF | IN_MOVE_SELF | IN_ATTRIB;
    state_watch = add_watch(state_fd, watch_mask);
    module_watch = add_watch(module_fd, watch_mask);
    if (state_watch < 0 || module_watch < 0) {
        error("inotify_add_watch", state_watch < 0 ? state_watch : module_watch);
        finish(3, 0);
    }
#ifdef REAMICRO_TIMER_TEST
    /* A fixture FIFO holds the subscribe/recheck gap open deterministically. */
    if (exists_at(state_fd, "test-gate")) {
        write_at(state_fd, "test-watch-ready", "1\n");
        int gate = (int)SC4(openat, state_fd, "test-gate", O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0);
        if (gate >= 0) { char byte; SC3(read, gate, &byte, 1); CLOSE(gate); }
    }
#endif
    if (!active()) finish(0, 0);
    if (get_config(&after) < 0 || changed_config(&before, &after) ||
        next_value() != expected_next || drain_events()) finish(0, 1);

    const char *mode = "realtime_alarm\n";
    alarm_fd = create_alarm(CLOCK_REALTIME_ALARM);
    if (alarm_fd >= 0) {
        result = arm_timer(alarm_fd, CLOCK_REALTIME_ALARM, delay, absolute, 1, &target);
        if (result < 0) { CLOSE(alarm_fd); alarm_fd = (int)result; }
    }
    if (alarm_fd < 0) {
        mode = "boottime_alarm\n";
        alarm_fd = create_alarm(CLOCK_BOOTTIME_ALARM);
        if (alarm_fd >= 0) {
            result = arm_timer(alarm_fd, CLOCK_BOOTTIME_ALARM, delay, absolute, 0, 0);
            if (result < 0) { CLOSE(alarm_fd); alarm_fd = (int)result; }
        }
        struct timespec real;
        if (SC2(clock_gettime, CLOCK_REALTIME, &real) < 0) finish(3, 0);
        target = absolute ? absolute : (uint64_t)real.tv_sec + delay;
    }
    if (alarm_fd < 0) {
        error("alarm clock unavailable (CAP_WAKE_ALARM/kernel)", alarm_fd);
        finish(3, 0);
    }
    /* A non-wakeup wall-clock guard also detects changes with BOOTTIME_ALARM. */
    clock_fd = (int)SC2(timerfd_create, CLOCK_REALTIME, TFD_CLOEXEC | TFD_NONBLOCK);
    if (clock_fd < 0 || arm_timer(clock_fd, CLOCK_REALTIME, delay, absolute, 1, 0) < 0) {
        error("wall-clock guard unavailable", -EINVAL);
        finish(3, 0);
    }
    char deadline[32];
    char *end = number(deadline, target);
    copy(end, "\n");
    if (write_at(state_fd, "wake.pid", pid_text) < 0) finish(3, 0);
    published = 1;
    if (write_at(state_fd, "wake-at", deadline) < 0 || write_at(state_fd, "wake-mode", mode) < 0)
        finish(3, 0);
    if (!active()) finish(0, 0);
    if (get_config(&after) < 0 || changed_config(&before, &after) ||
        next_value() != expected_next || drain_events()) finish(0, 1);
    armed = 1;
    /* Parent acquired the bounded lock before scheduling; release only after arm. */
    result = lock_file(1);
    if (result < 0) { error("release scheduling wake lock", result); finish(4, 1); }

    struct pollfd fds[] = {
        {alarm_fd, POLLIN, 0}, {notify_fd, POLLIN, 0},
        {signal_fd, POLLIN, 0}, {clock_fd, POLLIN, 0},
    };
    for (;;) {
#ifdef __NR_ppoll
        result = SC4(ppoll, fds, sizeof fds / sizeof fds[0], 0, 0);
#else
        result = SC3(poll, fds, sizeof fds / sizeof fds[0], -1);
#endif
        if (result == -EINTR) continue;
        if (result < 0) { error("poll", result); finish(3, 1); }
        if (fds[2].revents || !active()) finish(0, 0);
        if (fds[1].revents && drain_events()) finish(0, 1);
        for (unsigned i = 0; i < sizeof fds / sizeof fds[0]; ++i) {
            if (fds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) finish(3, 1);
        }
        if (fds[0].revents || fds[3].revents) {
            uint64_t expirations;
            int fd = fds[0].revents ? alarm_fd : clock_fd;
            result = SC3(read, fd, &expirations, sizeof expirations);
            if (result == (long)sizeof expirations || result == -ECANCELED) finish(0, 1);
            if (result != -EAGAIN && result != -EINTR) {
                error("timerfd_read", result);
                finish(3, 1);
            }
        }
    }
}

#if defined(__aarch64__)
__attribute__((naked, noreturn)) void _start(void) {
    __asm__ volatile("mov x0, sp\nbl wake_entry\n");
}
#else
__attribute__((naked, noreturn)) void _start(void) {
    __asm__ volatile("mov %rsp, %rdi\nandq $-16, %rsp\ncall wake_entry\n");
}
#endif
