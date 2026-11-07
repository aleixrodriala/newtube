#!/bin/bash
# Runs a command with the Pixel on LTE: Wi-Fi off (guarded) for the command's duration, back on
# afterwards. Same rules as pixel-lte-run.sh: the Wi-Fi toggle needs guard.sh start (NewTube or
# launcher focused, no shade, awake, no call); the restore falls back to "no call active" after
# 5 min of failed guards; a phone-side timer restores Wi-Fi after 4 h if this PC dies; the command
# is stopped if Wi-Fi comes back on mid-run (its results would no longer be LTE).
#
# Network toggles never use the in-call opt-in: every guard here runs with GUARD_ALLOW_CALL unset
# (the owner's rule, 2026-09-29: dropping Wi-Fi mid-call can cut a Wi-Fi-calling or WhatsApp call).
#
# Usage: NETBENCH_SERIAL=<adb serial> pixel-lte-wrap.sh <log-file> <command...>
# The guard (device/guard.sh) and the wrapped command read the same NETBENCH_SERIAL.
set -u
S=${NETBENCH_SERIAL:?set NETBENCH_SERIAL to the adb serial of the phone}
export NETBENCH_SERIAL
GUARD=$(cd "$(dirname "$0")" && pwd)/device/guard.sh
LOG=$1; shift
say() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
adbs() { adb -s $S "$@"; }
wifi_on() { adbs shell cmd wifi status 2>/dev/null | grep -q "Wifi is enabled"; }

# The phone-side safety timers: `sh -c "sleep 14400; ...; svc wifi enable"`, found by parsing ps on
# this host (a pattern run on the phone matches its own command line). kill_timers kills their sh
# with -9 (a TERM waits behind the sleep: two timers outlived their wrappers, 2026-09-29) and checks.
timer_pids() {
  adbs shell ps -A -o PID,ARGS 2>/dev/null | tr -d '\r' | awk '$2=="sh" && $3=="-c" && $4=="sleep" && $5=="14400;" {print $1}'
}
kill_timers() {  # label
  local p left
  p=$(timer_pids | tr '\n' ' ')
  [ -n "${p// /}" ] && adbs shell "kill -9 $p" 2>/dev/null
  # Their child sleep outlives a -9 to its sh (reparented to init, harmless but left running).
  local s
  s=$(adbs shell ps -A -o PID,ARGS 2>/dev/null | tr -d '\r' | awk '$2=="sleep" && $3=="14400" {print $1}' | tr '\n' ' ')
  [ -n "${s// /}" ] && adbs shell "kill -9 $s" 2>/dev/null
  adbs shell rm -f /data/local/tmp/netbench-timer.pid
  left=$(timer_pids | tr '\n' ' ')
  say "$1: phone-side timers killed [${p:-none}], left [${left:-none}]"
  [ -z "${left// /}" ]
}

restore() {
  [ -n "${CPID:-}" ] && kill "$CPID" 2>/dev/null
  if ! wifi_on; then
    for i in $(seq 1 10); do
      if env -u GUARD_ALLOW_CALL "$GUARD" start >>"$LOG" 2>&1; then break; fi
      if [ "$i" -eq 10 ]; then
        # "No call" needs a READABLE call state: an unplugged phone reads empty, and that is not "no
        # call" (2026-09-29 17:32: the owner unplugged it mid-call; this loop took the empty read for
        # "no call" and logged a Wi-Fi enable that never reached the phone). Wait for the phone, up to
        # 4.5 h (its own timer re-enables Wi-Fi at 4 h).
        waited=0
        until st=$(adbs shell dumpsys telephony.registry 2>/dev/null | grep -a -o "mCallState=[0-9]" | sort -u | tr '\n' ' ') \
              && [ -n "$st" ] && ! echo "$st" | grep -q "mCallState=[12]"; do
          if [ $waited -ge 16200 ]; then say "restore: phone unreachable or in a call for 4.5 h"; break; fi
          sleep 15; waited=$((waited + 15))
        done
        say "restore: guard kept failing for 5 min; call state [${st:-unreadable}]: re-enabling Wi-Fi"
      fi
      sleep 30
    done
    for i in 1 2 3; do adbs shell svc wifi enable 2>/dev/null; sleep 3; wifi_on && break; done
    if wifi_on; then say "restore: Wi-Fi enabled (checked)"
    else say "restore: Wi-Fi NOT confirmed on (phone unreachable?): the phone-side timer stays armed"; fi
  fi
  if wifi_on; then kill_timers "restore"; else say "restore: Wi-Fi not confirmed on: the phone-side timer stays armed"; fi
  sleep 5
  adbs shell cmd wifi status | head -1 | tee -a "$LOG"
  say "restore: done"
}
trap restore EXIT

# Pre-flight: no stale timer from an earlier run may outlive this one.
kill_timers "pre-flight" || { say "a stale phone-side timer would not die: not switching Wi-Fi off"; exit 1; }
adbs shell 'nohup sh -c "sleep 14400; while dumpsys telephony.registry | grep -q \"mCallState=[12]\"; do sleep 15; done; svc wifi enable" >/dev/null 2>&1 & echo $! > /data/local/tmp/netbench-timer.pid'
# The guard's own status, not tee's: a failed guard must stop the Wi-Fi switch.
env -u GUARD_ALLOW_CALL "$GUARD" start | tee -a "$LOG"
[ "${PIPESTATUS[0]}" -eq 0 ] || { say "guard failed: not switching Wi-Fi off"; exit 1; }
adbs shell svc wifi disable
say "Wi-Fi disabled"
for i in $(seq 1 30); do
  adbs shell dumpsys connectivity 2>/dev/null | grep -a -m1 "Active default network" | grep -q -v "none" && \
    adbs shell ping -c1 -W3 8.8.8.8 >/dev/null 2>&1 && break
  sleep 2
done
say "cell network up: $(adbs shell dumpsys connectivity | grep -a -m1 -o 'NetworkAgentInfo{network{[0-9]*}  handle{[0-9]*}  ni{[A-Z]*' | head -1)"

"$@" >>"$LOG" 2>&1 &
CPID=$!
say "command pid $CPID: $*"
while kill -0 "$CPID" 2>/dev/null; do
  sleep 30
  if wifi_on; then say "Wi-Fi came back on during the run: stopping it"; kill "$CPID"; break; fi
done
wait "$CPID" 2>/dev/null
say "command finished (exit $?)"
