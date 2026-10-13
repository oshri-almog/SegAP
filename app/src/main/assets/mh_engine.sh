#!/system/bin/sh
# SegAP engine - runs as root (via su).
#
# One access point, several passwords. Each password (hostapd keyid) is a "group"
# with its own subnet, DHCP range, DNS and firewall policy.
#
# usage:
#   mh_engine.sh start <cfgdir>   start (or restart) with config files from <cfgdir>
#   mh_engine.sh stop             stop and undo every change
#   mh_engine.sh status [helper]  print state and connected clients (helper = path of the native stats tool)
#   mh_engine.sh daemon           (internal) event loop + watchdog
#   mh_engine.sh ts-install <tgz> <sha256>   install the official Tailscale package (exit nodes)
#   mh_engine.sh ts-up <group> [hostname]    start a group's Tailscale instance (prints ts-status)
#   mh_engine.sh ts-status <group>           instance state, login URL and status JSON
#   mh_engine.sh ts-down <group> | ts-logout <group>
#
# <cfgdir> must contain:
#   engine.conf  KEY=VALUE lines: IFACE, MAC, WIFI_MODE (keep|off), UPSTREAM (auto|<iface>), DEFAULT_GROUP, WL_HELPER
#   hostapd.conf complete hostapd config (wpa_psk_file must point to $HD/psk.txt)
#   psk.txt      hostapd wpa_psk_file, one "keyid=<group> 00:00:00:00:00:00 <passphrase>" per group
#   groups.conf  ID GW PREFIX NETCIDR NETMASK INTERNET(0|1) ISOLATE(0|1) DHCP_START DHCP_END LEASE DNS(csv) TAILSCALE(0|1) REACH(csv ids|-)
#   static.conf  MAC GROUP IP   (optional fixed addresses)
#   deny.txt     blocked MACs   (optional)
#   ts.conf      GROUP EXIT_NODE_IP|- SUBNET(0|1) HOSTNAME   (optional: groups with their own Tailscale instance)
#
# All sh variables are global: helpers called from loops use their own prefixes (h_, p_, d_...).

ST=/data/adb/multihotspot
HD=/data/vendor/wifi/hostapd/mh
CTRL_DIR=/data/vendor/wifi/hostapd/ctrl
HOSTAPD=/vendor/bin/hw/hostapd
DNSMASQ=/system/bin/dnsmasq
PREF=21500     # ip rule: forwarded traffic from the AP -> upstream table
TS_PREF=21400  # ip rule: groups with Tailscale access -> Tailscale VPN table (checked first)
LOCAL_PREF=10500  # ip rule: the phone's own traffic to hotspot subnets -> local_network (see start())
IPT="iptables -w"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $ST/engine.log; }
fail() { log "ERROR: $*"; echo "ERROR: $*"; echo failed > $ST/state; echo "$*" > $ST/reason; }

cfg() { sed -n "s/^$1=//p" $ST/cfg/engine.conf 2>/dev/null | head -1; }

is_mac() { echo "$1" | grep -qiE '^([0-9a-f]{2}:){5}[0-9a-f]{2}$'; }

# pid exists AND its command line contains $2. Pid files survive reboots: never signal a recycled pid.
pid_ok() {
  [ -n "$1" ] && [ -r "/proc/$1/cmdline" ] || return 1
  # Never feed /proc/<pid>/cmdline straight into tr: if the process exits mid-read, toybox tr spins forever on the
  # read error (this hung "Apply"). cat exits on errors, and the timeout bounds it anyway.
  timeout 2 cat "/proc/$1/cmdline" 2>/dev/null | tr '\0' ' ' | grep -q -- "$2"
}

hostapd_pid() { pgrep -f "hostapd $HD/hostapd[.]conf" | head -1; }
dnsmasq_pid() { d_p=$(cat $ST/dnsmasq.pid 2>/dev/null); pid_ok "$d_p" dnsmasq && echo "$d_p"; }
daemon_alive() { pid_ok "$(cat $ST/daemon.pid 2>/dev/null)" "mh_engine"; }

# Android's own hostapd service (Samsung Mobile Hotspot, Quick Share, Smart View...) shares the radio with us.
system_hotspot_running() { [ "$(getprop init.svc.hostapd)" = running ]; }

# ...or Android tried to start it recently: its attempt alone tears our AP down, even if it then fails itself.
system_hotspot_recent() {
  system_hotspot_running && return 0
  r_since=$(date -d @$(( $(date +%s) - 40 )) '+%m-%d %H:%M:%S.000' 2>/dev/null) || return 1
  logcat -d -T "$r_since" -s WifiService:I 2>/dev/null | grep -q -E 'startTetheredHotspot|startSoftAp|startLocalOnlyHotspot'
}

# settings wifi_on: 1 = on, 2 = on in airplane mode.
wifi_is_on() { case "$(settings get global wifi_on 2>/dev/null)" in 1|2) return 0 ;; esac; return 1; }

# Another tethering (USB, Bluetooth, Android hotspot) has rules in netd's chain besides the final DROP.
other_tethering_active() { $IPT -S tetherctrl_FORWARD 2>/dev/null | grep -v -e '^-N ' -e '^-A tetherctrl_FORWARD -j DROP$' | grep -q .; }

# in_cidr <ip> <net/prefix>
in_cidr() {
  awk -v ip="$1" -v cidr="$2" 'function n(a,  p) { split(a, p, "."); return ((p[1]*256 + p[2])*256 + p[3])*256 + p[4] }
    BEGIN { split(cidr, c, "/"); m = 2 ^ (32 - c[2]); exit !(int(n(ip) / m) == int(n(c[1]) / m)) }'
}

# State left from before a reboot is meaningless (processes, sysctls, rules are gone).
stale_boot() { [ -f $ST/boot_id ] && [ "$(cat $ST/boot_id)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; }
reset_stale() {
  stale_boot || return 1
  rm -f $ST/*.pid $ST/*.orig $ST/pins $ST/boot_id
  rm -f $ST/ts/*/*.pid $ST/ts/*/exit $ST/ts/*/route $ST/ts/*/routeip $ST/ts/*/bstate $ST/ts/*/subnet $ST/ts/*/subnetok $ST/ts/ip_forward.orig
  rm -rf $ST/ts/*/.lock
  : > $ST/connected; : > $ST/upstream; : > $ST/map
  [ "$(cat $ST/state 2>/dev/null)" = stopped ] || echo stopped > $ST/state
  return 0
}

# ---------------------------------------------------------------- groups

# group_field <id> <column>   (1-based column of groups.conf)
group_field() { awk -v g="$1" -v c="$2" '$1==g {print $c; exit}' $ST/cfg/groups.conf; }

group_exists() { [ -n "$1" ] && awk -v g="$1" '$1==g {f=1} END {exit !f}' $ST/cfg/groups.conf; }

# ---------------------------------------------------------------- upstream

upstream_table() {
  U=$(cfg UPSTREAM)
  if [ -z "$U" ] || [ "$U" = auto ]; then
    # Ask as "protected from VPN" (netd's mark 0x20000): root's own lookups follow the Tailscale app's VPN, and
    # with an exit node selected there that VPN covers 8.8.8.8 - it must never become the hotspot's upstream.
    ip route get 8.8.8.8 mark 0x20000 2>/dev/null | grep -v ' dev tun' | sed -n 's/.* table \([^ ]*\).*/\1/p' | head -1
  else
    echo "$U"
  fi
}

apply_upstream() {
  IF=$(cfg IFACE)
  T=$(upstream_table)
  if [ -z "$T" ]; then
    [ -s $ST/upstream ] && log "upstream lost"
    while ip rule del pref $PREF 2>/dev/null; do :; done
    : > $ST/upstream
    return 1
  fi
  if [ "$T" = "$(cat $ST/upstream 2>/dev/null)" ] && ip rule | grep -q "^$PREF:.*iif $IF"; then
    return 0
  fi
  while ip rule del pref $PREF 2>/dev/null; do :; done
  ip rule add pref $PREF iif $IF lookup $T
  echo "$T" > $ST/upstream
  log "upstream -> $T"
}

# ---------------------------------------------------------------- tailscale
# The Tailscale app is an Android VPN: its tun interface holds the node's 100.64.0.0/10 address and Android
# keeps a routing table named after it with the tailnet routes. Groups with Tailscale access look that table
# up first; destinations it doesn't cover fall through to the upstream rule.

# Newest interface holding a 100.64.0.0/10 address: while Tailscale rebuilds its VPN (any change: peers, exit
# node, network) the old and new tun briefly coexist, and the name alternates tun0/tun1.
tailscale_iface() {
  ip -4 -o addr show 2>/dev/null | awk '{ split($4, a, "/"); split(a[1], o, ".");
    if (o[1] == 100 && o[2] >= 64 && o[2] <= 127 && $1 + 0 > m) { m = $1 + 0; n = $2 } } END { if (n != "") print n }'
}

# $ST/tailscale = "<iface> <ifindex> <app-exit 0|1>": a rebuilt tun gets a new ifindex and so a new table number
# (netd: 1000 + ifindex), even under the same name - the rules must follow it.
apply_tailscale() {
  IF=$(cfg IFACE)
  a_ts=$(tailscale_iface)
  a_want=$(awk '$1 !~ /^#/ && $12 == "1" {print $4}' $ST/cfg/groups.conf)
  [ -z "$a_want" ] && a_ts=""
  a_key=""; a_exit=0
  if [ -n "$a_ts" ]; then
    [ -n "$(ip -4 route show table $a_ts match 1.1.1.1 2>/dev/null)" ] && a_exit=1
    a_key="$a_ts $(cat /sys/class/net/$a_ts/ifindex 2>/dev/null) $a_exit"
  fi
  if [ "$a_key" = "$(cat $ST/tailscale 2>/dev/null)" ] && { [ -z "$a_ts" ] || ip rule | grep -q "^$TS_PREF:"; }; then
    return 0
  fi
  while ip rule del pref $TS_PREF 2>/dev/null; do :; done
  if [ -n "$a_ts" ]; then
    # Remember the tailnet's subnet routes while they're visible (never replace them with an empty list: a table
    # being rebuilt is briefly empty) - needed if the app's exit node shows up as blocks instead of a default route.
    if [ $a_exit = 0 ]; then
      ip -4 route show table $a_ts 2>/dev/null |
        awk '$1 != "default" && $1 ~ /^[0-9]/ && $1 !~ /^100\./ {print $1}' > $ST/ts_subnets.new
      if [ -s $ST/ts_subnets.new ]; then mv $ST/ts_subnets.new $ST/ts_subnets; else rm -f $ST/ts_subnets.new; fi
    fi
    # Tailscale access means the tailnet, not the app's exit node. A plain default route (the app's exit node
    # without "Allow LAN access") is skipped with suppress_prefixlength 0 and every tailnet/subnet route still
    # applies. With LAN access the exit node is a set of blocks around the local networks: then only tailnet
    # addresses and the remembered subnet routes go there.
    a_def=""; [ $a_exit = 1 ] && a_def=$(ip -4 route show table $a_ts default 2>/dev/null)
    for a_net in $a_want; do
      if [ $a_exit = 0 ]; then
        ip rule add pref $TS_PREF from $a_net iif $IF lookup $a_ts
      elif [ -n "$a_def" ]; then
        ip rule add pref $TS_PREF from $a_net iif $IF lookup $a_ts suppress_prefixlength 0
      else
        ip rule add pref $TS_PREF from $a_net to 100.64.0.0/10 iif $IF lookup $a_ts
        for a_s in $(cat $ST/ts_subnets 2>/dev/null); do ip rule add pref $TS_PREF from $a_net to $a_s iif $IF lookup $a_ts; done
      fi
    done
    if [ $a_exit = 1 ]; then log "tailscale -> $a_ts (the Tailscale app uses an exit node: tailnet only)"; else log "tailscale -> $a_ts"; fi
  elif [ -n "$a_want" ] && [ -s $ST/tailscale ]; then
    log "tailscale disconnected"
  fi
  echo "$a_key" > $ST/tailscale
}

# ---------------------------------------------------------------- per-group Tailscale instances (exit node, subnet route)
# A group can send its internet out through a Tailscale exit node of its own. The Tailscale app holds one exit
# node for the whole phone, so SegAP runs a separate tailscaled per such group (the official static binary; a
# separate device in the tailnet), each in its own network namespace, joined to the phone by a veth pair:
#   phone: sg<N> 198.18.<N>.1/30  <--->  198.18.<N>.2/30 sg<N>p  [namespace: tailscaled, tun "tailscale0"]
# Inside its namespace tailscaled installs its usual policy routing (rules 52xx, table 52) without touching
# Android's. The group's traffic enters through table TS_TABLE+N; the instance's own traffic (control, DERP,
# WireGuard) leaves through the phone's upstream. <N> is the group's slot, kept in $TSD/<group>/slot.
# A group can also be shared with the tailnet: its instance advertises the group's subnet as a subnet route, and
# tailnet devices reach the group's devices through it (NATed to the instance's end of the link).
# cfg/ts.conf: "<group> <exit node 100.x IP or -> <share subnet 0|1> <hostname>" per group with an instance.
TSD=$ST/ts
TS_LOCAL_PREF=10510   # ip rule: the phone's traffic to instance links -> local_network
TS_UP_PREF=21310      # ip rule: instance uplinks -> upstream table
EXIT_PREF=21450       # ip rule: groups using an exit node -> their instance (after TS_PREF, before PREF)
TS_TABLE=60000        # + slot: "default via the instance"
TS_HOLD=900000000     # + slot: the namespace holder runs "sleep <this>" (unique per slot, ~28 years)

ts_bin_ok() { [ -x $TSD/bin/tailscaled ] && [ -x $TSD/bin/tailscale ]; }

# tsc <group> <tailscale args...>: the CLI of <group>'s instance (bounded: a wedged daemon must not hang us).
tsc() { c_g=$1; shift; timeout 20 $TSD/bin/tailscale --socket=$TSD/$c_g/tailscaled.sock "$@"; }

ts_slot() {
  [ -s $TSD/$1/slot ] && { cat $TSD/$1/slot; return 0; }
  sl_n=1
  while [ $sl_n -le 250 ] && cat $TSD/*/slot 2>/dev/null | grep -qx "$sl_n"; do sl_n=$((sl_n + 1)); done
  [ $sl_n -le 250 ] || return 1
  echo $sl_n > $TSD/$1/slot; echo $sl_n
}

# ts_holder <group> <slot>: pid of the group's namespace holder, if alive.
ts_holder() { ho_p=$(cat $TSD/$1/holder.pid 2>/dev/null); pid_ok "$ho_p" "sleep $((TS_HOLD + $2)) " && echo "$ho_p"; }

# ts_ready <group> <slot>: the namespace was set up completely (ts_net_up writes the holder's pid to "netready"
# only at the very end; an engine killed half-way leaves it missing) and its link still exists.
ts_ready() {
  rd_h=$(ts_holder $1 $2)
  [ -n "$rd_h" ] && [ "$(cat $TSD/$1/netready 2>/dev/null)" = "$rd_h" ] && [ -d /sys/class/net/sg$2 ]
}

ts_daemon_ok() { pid_ok "$(cat $TSD/$1/tailscaled.pid 2>/dev/null)" "statedir=$TSD/$1 "; }

ts_daemon_kill() {
  dk_p=$(cat $TSD/$1/tailscaled.pid 2>/dev/null)
  ts_daemon_ok $1 || return 0
  kill $dk_p 2>/dev/null
  dk_w=0; while [ $dk_w -lt 10 ] && ts_daemon_ok $1; do sleep 0.3; dk_w=$((dk_w + 1)); done
  ts_daemon_ok $1 && kill -9 $dk_p 2>/dev/null
  rm -f $TSD/$1/tailscaled.pid $TSD/$1/tailscaled.sock
}

# The watchdog and the app (network editor) may both bring up or tear down the same instance: one at a time.
# mkdir is atomic. The engine clears all locks when it kills its watchdog (kill_engine_procs); any other stale
# lock is broken after 30 s. The group directory itself may vanish meanwhile (ts_logout): re-create it.
ts_lock() {
  lk_w=0
  while ! { mkdir -p $TSD/$1 2>/dev/null; mkdir $TSD/$1/.lock 2>/dev/null; }; do
    lk_w=$((lk_w + 1))
    [ $lk_w -ge 60 ] && { rm -rf $TSD/$1/.lock; mkdir -p $TSD/$1/.lock 2>/dev/null; break; }
    sleep 0.5
  done
}
ts_unlock() { rmdir $TSD/$1/.lock 2>/dev/null; }

# Main-namespace firewall for the instances. Their own traffic (control, DERP, WireGuard) may go out to the
# upstream. Anything between an instance and the AP interface is left to the hotspot's chain (exit-node traffic,
# replies, shared subnets - after its anti-spoof check). Nothing from an instance may reach the phone itself:
# a shared subnet's gateway address is the phone.
ts_fw() {
  fw_ap=$(cfg IFACE); fw_ap=${fw_ap:-swlan0}
  if $IPT -C FORWARD -j sg_FWD 2>/dev/null && $IPT -C sg_FWD -i sg+ -o $fw_ap -j RETURN 2>/dev/null \
    && $IPT -t nat -C POSTROUTING -j sg_NAT 2>/dev/null && $IPT -C INPUT -j sg_IN 2>/dev/null; then
    return 0
  fi
  $IPT -N sg_FWD 2>/dev/null; $IPT -F sg_FWD
  $IPT -A sg_FWD -i $fw_ap -o sg+ -j RETURN
  $IPT -A sg_FWD -i sg+ -o $fw_ap -j RETURN
  $IPT -A sg_FWD -o sg+ -d 198.18.0.0/16 -m state --state ESTABLISHED,RELATED -j ACCEPT
  $IPT -A sg_FWD -i sg+ -o sg+ -j DROP
  $IPT -A sg_FWD -i sg+ -s 198.18.0.0/16 -j ACCEPT
  $IPT -C FORWARD -j sg_FWD 2>/dev/null || $IPT -I FORWARD 1 -j sg_FWD
  $IPT -N sg_IN 2>/dev/null; $IPT -F sg_IN
  $IPT -A sg_IN -i sg+ -m state --state ESTABLISHED,RELATED -j ACCEPT
  $IPT -A sg_IN -i sg+ -j DROP
  $IPT -C INPUT -j sg_IN 2>/dev/null || $IPT -I INPUT 1 -j sg_IN
  $IPT -t nat -N sg_NAT 2>/dev/null; $IPT -t nat -F sg_NAT
  $IPT -t nat -A sg_NAT -s 198.18.0.0/16 ! -o sg+ -j MASQUERADE
  $IPT -t nat -C POSTROUTING -j sg_NAT 2>/dev/null || $IPT -t nat -I POSTROUTING 1 -j sg_NAT
}

ts_fw_teardown() {
  while $IPT -D FORWARD -j sg_FWD 2>/dev/null; do :; done
  while $IPT -D INPUT -j sg_IN 2>/dev/null; do :; done
  while $IPT -t nat -D POSTROUTING -j sg_NAT 2>/dev/null; do :; done
  for fw_c in sg_FWD sg_IN; do $IPT -F $fw_c 2>/dev/null; $IPT -X $fw_c 2>/dev/null; done
  $IPT -t nat -F sg_NAT 2>/dev/null; $IPT -t nat -X sg_NAT 2>/dev/null
}

# ts_uplink <slot>: the instance's own traffic follows the phone's current upstream. Returns 2 when it moved
# from another upstream: the instance's connections are NATed to the old one and must be reopened.
ts_uplink() {
  ul_v=sg$1; ul_t=$(upstream_table)
  [ -n "$ul_t" ] && ip rule | grep -qE "^$TS_UP_PREF:.* iif $ul_v lookup $ul_t( |\$)" && return 0
  ul_old=$(ip rule | grep -E "^$TS_UP_PREF:.* iif $ul_v lookup " | head -1)
  while ip rule del pref $TS_UP_PREF iif $ul_v 2>/dev/null; do :; done
  [ -n "$ul_t" ] || return 0
  ip rule add pref $TS_UP_PREF iif $ul_v lookup $ul_t
  [ -n "$ul_old" ] && return 2
  return 0
}

# ts_net_down <group> <slot>: remove the group's link, routes and rules (the namespace dies with its holder).
ts_net_down() {
  ip link del sg$2 2>/dev/null
  while ip rule del pref $TS_LOCAL_PREF to 198.18.$2.0/30 2>/dev/null; do :; done
  while ip rule del pref $TS_UP_PREF iif sg$2 2>/dev/null; do :; done
  while ip rule del pref $EXIT_PREF lookup $((TS_TABLE + $2)) 2>/dev/null; do :; done
  ip route flush table $((TS_TABLE + $2)) 2>/dev/null
  rm -f $TSD/$1/netready
}

# ts_relink <slot>: drop every connection NATed onto the group's link (removing an address makes the kernel flush
# MASQUERADE entries of that interface), e.g. after the group fell back to the normal upstream: those flows
# would otherwise leave the upstream with a 198.18 source and hang.
ts_relink() {
  ip addr del 198.18.$1.1/30 dev sg$1 2>/dev/null
  ip addr add 198.18.$1.1/30 dev sg$1
  ip route replace 198.18.$1.0/30 dev sg$1 table local_network
  ip route replace default via 198.18.$1.2 dev sg$1 table $((TS_TABLE + $1))
}

# ts_net_up <group>: make sure the group's instance runs: namespace (rebuilt if missing or half-built), link,
# firewall, uplink and tailscaled.
ts_net_up() {
  tu_g=$1
  ts_bin_ok || { echo "TSERR=Tailscale for SegAP is not installed"; return 1; }
  mkdir -p $TSD/$tu_g; chmod 700 $TSD $TSD/$tu_g
  tu_n=$(ts_slot $tu_g) || { echo "TSERR=Too many Tailscale instances"; return 1; }
  tu_v=sg$tu_n; tu_vn=sg${tu_n}p
  # Instances talk to the internet through the phone: forwarding must be on even while the hotspot is off.
  if [ "$(cat /proc/sys/net/ipv4/ip_forward)" != 1 ]; then
    [ -f $TSD/ip_forward.orig ] || cat /proc/sys/net/ipv4/ip_forward > $TSD/ip_forward.orig
    echo 1 > /proc/sys/net/ipv4/ip_forward
  fi
  if ! ts_ready $tu_g $tu_n; then
    ts_daemon_kill $tu_g
    tu_h=$(ts_holder $tu_g $tu_n); [ -n "$tu_h" ] && kill $tu_h 2>/dev/null
    ts_net_down $tu_g $tu_n
    rm -f $TSD/$tu_g/holder.pid $TSD/$tu_g/subnet
    [ -e /dev/net/tun ] || { mkdir -p /dev/net; ln -s ../tun /dev/net/tun; }
    unshare -n sleep $((TS_HOLD + tu_n)) </dev/null >/dev/null 2>&1 &
    echo $! > $TSD/$tu_g/holder.pid
    sleep 1
    tu_h=$(ts_holder $tu_g $tu_n)
    [ -n "$tu_h" ] || { echo "TSERR=Could not create a network namespace"; return 1; }
    if ! { ip link add $tu_v type veth peer name $tu_vn && ip link set $tu_vn netns $tu_h; }; then
      kill $tu_h 2>/dev/null; ts_net_down $tu_g $tu_n
      echo "TSERR=Could not create the link to the Tailscale instance"; return 1
    fi
    ip addr add 198.18.$tu_n.1/30 dev $tu_v
    ip link set $tu_v up
    ip route replace 198.18.$tu_n.0/30 dev $tu_v table local_network
    ip rule add pref $TS_LOCAL_PREF to 198.18.$tu_n.0/30 lookup local_network
    ip route replace default via 198.18.$tu_n.2 dev $tu_v table $((TS_TABLE + tu_n))
    # Inside, in order:
    # - default route back through the phone: tailscaled's own (marked) traffic uses it;
    # - replies to the phone's end of the link must not follow table 52 into the tunnel (rule 100);
    # - whatever comes out of the tunnel goes to the link (rule 120): replies of flows NATed for the normal upstream
    #   before the group switched to the exit node then reach the device (as the server's reset) instead of
    #   looping back into the tunnel, so its apps reconnect at once;
    # - forwarded traffic may only go to public addresses (not the tailnet, not the exit node's LAN or its
    #   subnet routes), is NATed to the node's tailnet address (exit nodes only accept that) and gets its TCP MSS
    #   clamped for the 1280-byte tunnel;
    # - nothing is forwarded from the tunnel into the link except replies, and for a shared subnet what
    #   ts_set_subnet puts into SG_RT.
    # toybox nsenter keeps parsing options after the command: "--" is required.
    tu_ok=$(nsenter -t $tu_h -n -- sh -c "
      set -e
      ip link set lo up
      ip addr add 198.18.$tu_n.2/30 dev $tu_vn; ip link set $tu_vn up
      ip route add default via 198.18.$tu_n.1
      ip rule add pref 100 to 198.18.$tu_n.0/30 lookup main
      ip rule add pref 120 iif tailscale0 lookup main
      echo 1 > /proc/sys/net/ipv4/ip_forward
      $IPT -N SG_RT; $IPT -I FORWARD 1 -j SG_RT
      $IPT -t nat -N SG_RTNAT; $IPT -t nat -I POSTROUTING 1 -j SG_RTNAT
      $IPT -t nat -A POSTROUTING -o tailscale0 -j MASQUERADE
      for d in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 198.18.0.0/15 224.0.0.0/3; do
        $IPT -A FORWARD -i $tu_vn -o tailscale0 -d \$d -j DROP
      done
      $IPT -A FORWARD -i $tu_vn -o tailscale0 -j ACCEPT
      $IPT -A FORWARD -i tailscale0 -o $tu_vn -m state --state ESTABLISHED,RELATED -j ACCEPT
      $IPT -P FORWARD DROP
      $IPT -t mangle -A FORWARD -o tailscale0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
      $IPT -t mangle -A FORWARD -i tailscale0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1240
      echo SETUP-OK" 2>&1 | tail -1)
    if [ "$tu_ok" != SETUP-OK ]; then
      kill $tu_h 2>/dev/null; ts_net_down $tu_g $tu_n
      echo "TSERR=Could not set up the Tailscale instance's network ($tu_ok)"; return 1
    fi
    echo $tu_h > $TSD/$tu_g/netready
  fi
  ts_fw
  ts_uplink $tu_n
  [ $? = 2 ] && { log "$tu_g: upstream changed - restarting its Tailscale"; ts_daemon_kill $tu_g; }
  # Appending (">>"), so truncating a large log takes effect while the daemon runs.
  [ "$(stat -c %s $TSD/$tu_g/tailscaled.log 2>/dev/null || echo 0)" -gt 2000000 ] && : > $TSD/$tu_g/tailscaled.log
  if ! ts_daemon_ok $tu_g; then
    rm -f $TSD/$tu_g/tailscaled.sock
    HOME=$TSD/$tu_g nsenter -t $(ts_holder $tu_g $tu_n) -n -- $TSD/bin/tailscaled --tun=tailscale0 --statedir=$TSD/$tu_g \
      --socket=$TSD/$tu_g/tailscaled.sock --port=0 --no-logs-no-support </dev/null >>$TSD/$tu_g/tailscaled.log 2>&1 &
    echo $! > $TSD/$tu_g/tailscaled.pid
    tu_w=0; while [ $tu_w -lt 20 ] && [ ! -S $TSD/$tu_g/tailscaled.sock ]; do sleep 0.5; tu_w=$((tu_w + 1)); done
    sleep 1
  fi
  return 0
}

ts_backend() { tsc $1 status --json --peers=false 2>/dev/null | sed -n 's/.*"BackendState": "\([A-Za-z]*\)".*/\1/p' | head -1; }

# ts_session <group> <hostname> <BackendState>: keep the device name; when it isn't signed in, run "tailscale up",
# which prints the login URL (ts_info shows it) and waits for the login. "up --reset" drops the exit node and the
# advertised route: forget that they were set, so they're set again after the login.
ts_session() {
  ss_host=${2:-segap-$1}
  case "$3" in
    Running|Starting)
      if [ "$ss_host" != "$(cat $TSD/$1/host 2>/dev/null)" ]; then
        tsc $1 set --hostname="$ss_host" >/dev/null 2>&1 && echo "$ss_host" > $TSD/$1/host
      fi
      ;;
    NeedsLogin|Stopped|NoState)
      # NoState right after a start is normal for a signed-in node: only a node without state needs a login then.
      [ "$3" = NoState ] && [ -s $TSD/$1/tailscaled.state ] && return 0
      if ! pgrep -f "$TSD/$1/tailscaled[.]sock up" >/dev/null 2>&1; then
        rm -f $TSD/$1/subnet
        $TSD/bin/tailscale --socket=$TSD/$1/tailscaled.sock up --json --reset --hostname="$ss_host" \
          --accept-dns=false --accept-routes=false --netfilter-mode=off --timeout=600s </dev/null >$TSD/$1/up.json 2>&1 &
        echo "$ss_host" > $TSD/$1/host
      fi
      ;;
    # NeedsMachineAuth: signed in, waiting for an admin to approve the device - nothing to do here.
  esac
}

# ts_up <group> [hostname]: instance running plus, if needed, a login started (the app's editor uses this).
ts_up() {
  ts_net_up $1 || return 1
  ts_session $1 "$2" "$(ts_backend $1)"
  return 0
}

# ts_probe <group> [subnet]: "<BackendState|down> <exit node online 0|1> <subnet route approved 0|1> <exit node IP|->"
# from the instance itself (an approved, active route is listed in its PrimaryRoutes).
ts_probe() {
  tsc $1 status --json --peers=false 2>/dev/null | awk -v n="$2" '
    /"BackendState":/ { gsub(/[",]/, "", $2); b = $2 }
    /"ExitNodeStatus":/ { e = NR }
    e && NR <= e + 3 && /"Online": true/ { o = 1 }
    e && !ed && /"TailscaleIPs": \[/ { ti = 1; next }
    ti && /\]/ { ti = 0; ed = 1 }
    ti && x == "" { v = $0; gsub(/[ \t",]/, "", v); split(v, q, "/"); if (q[1] ~ /^[0-9]+\./) x = q[1] }
    /"PrimaryRoutes": \[/ { p = 1 }
    p && n != "" && index($0, "\"" n "\"") { a = 1 }
    p && /\]/ { p = 0 }
    END { print (b == "" ? "down" : b), o + 0, a + 0, (x == "" ? "-" : x) }'
}

# ts_set_exit <group> <100.x IP|->: point the instance at its exit node, or none.
ts_set_exit() {
  se_e=$2; [ "$se_e" = - ] && se_e=""
  tsc $1 set --exit-node="$se_e" --exit-node-allow-lan-access=false >/dev/null 2>&1 || return 1
  log "exit node for $1: ${se_e:-none}"
}

# ts_set_subnet <group> <slot> <subnet|->: share the group's subnet with the tailnet through its instance, or stop.
# Inside the namespace: traffic from the tailnet to the subnet is forwarded to the subnet only (never to the
# phone: the main namespace's sg_IN drops that) and NATed to this end of the link, so the devices' replies come
# back here instead of going to the Tailscale app's VPN. The tailnet still has to approve the route (admin
# console, or autoApprovers).
ts_set_subnet() {
  [ "$(cat $TSD/$1/subnet 2>/dev/null)" = "$3" ] && return 0
  sn_h=$(ts_holder $1 $2); [ -n "$sn_h" ] || return 1
  sn_a=""; [ "$3" != - ] && sn_a=$3
  tsc $1 set --advertise-routes="$sn_a" >/dev/null 2>&1 || return 1
  nsenter -t $sn_h -n -- sh -c "
    $IPT -F SG_RT; $IPT -t nat -F SG_RTNAT
    if [ -n '$sn_a' ]; then
      $IPT -A SG_RT -i tailscale0 -o sg${2}p -d $sn_a -j ACCEPT
      $IPT -A SG_RT -i sg${2}p -o tailscale0 -s $sn_a -m state --state ESTABLISHED,RELATED -j ACCEPT
      $IPT -t nat -A SG_RTNAT -o sg${2}p -d $sn_a -j MASQUERADE
    fi"
  echo "$3" > $TSD/$1/subnet
  if [ -n "$sn_a" ]; then log "$1: subnet $sn_a advertised to the tailnet"; else log "$1: subnet not advertised"; fi
}

# ts_down <group>: stop the group's instance and remove its namespace (keeps its login).
ts_down() {
  td_n=$(cat $TSD/$1/slot 2>/dev/null); [ -n "$td_n" ] || return 0
  pkill -f "$TSD/$1/tailscaled[.]sock up" 2>/dev/null
  ts_daemon_kill $1
  td_h=$(ts_holder $1 $td_n); [ -n "$td_h" ] && kill $td_h 2>/dev/null
  ts_net_down $1 $td_n
  rm -f $TSD/$1/tailscaled.pid $TSD/$1/holder.pid $TSD/$1/route $TSD/$1/routeip $TSD/$1/bstate $TSD/$1/tailscaled.sock \
    $TSD/$1/subnet $TSD/$1/subnetok
}

# No instance left: remove their firewall chains and undo our ip_forward change.
ts_idle() {
  for ti_d in $TSD/*/; do
    ti_g=$(basename "$ti_d"); ti_n=$(cat "$ti_d/slot" 2>/dev/null) || continue
    [ -n "$ti_n" ] && [ -n "$(ts_holder $ti_g $ti_n)" ] && return 0
  done
  ts_fw_teardown
  if [ -f $TSD/ip_forward.orig ]; then
    [ "$(cat $ST/state 2>/dev/null)" = running ] || cat $TSD/ip_forward.orig > /proc/sys/net/ipv4/ip_forward
    rm -f $TSD/ip_forward.orig
  fi
}

ts_down_all() {
  for ta_d in $TSD/*/; do
    [ -f "$ta_d/slot" ] || continue
    ta_g=$(basename "$ta_d"); ts_lock "$ta_g"; ts_down "$ta_g"; ts_unlock "$ta_g"
  done
  ts_idle
}

# Instances of groups that no longer have one.
ts_down_unused() {
  for tr_d in $TSD/*/; do
    tr_g=$(basename "$tr_d"); [ -f "$tr_d/slot" ] || continue
    awk -v g="$tr_g" '$1 == g {f = 1} END {exit !f}' $ST/cfg/ts.conf 2>/dev/null && continue
    ts_lock "$tr_g"; ts_down "$tr_g"; ts_unlock "$tr_g"
  done
}

# After a restart (Apply keeps the instances), route exit groups whose instance was active straight back into it,
# instead of leaving their devices on the normal upstream until the watchdog's next pass.
ts_resume_exits() {
  [ -s $ST/cfg/ts.conf ] || return 0
  while read -r x_g x_ip x_sn x_rest; do
    case "$x_g" in ''|\#*) continue ;; esac
    # Only if the instance still points at the node this config wants (Apply may have changed it).
    [ "$x_ip" != - ] && [ "$(cat $TSD/$x_g/route 2>/dev/null)" = active ] && [ "$(cat $TSD/$x_g/routeip 2>/dev/null)" = "$x_ip" ] || continue
    x_n=$(cat $TSD/$x_g/slot 2>/dev/null); x_net=$(group_field "$x_g" 4)
    [ -n "$x_n" ] && [ -n "$x_net" ] && ts_ready $x_g $x_n && ts_daemon_ok $x_g || continue
    ip rule add pref $EXIT_PREF from $x_net iif $IF lookup $((TS_TABLE + x_n))
  done < $ST/cfg/ts.conf
}

# Groups with an instance (ts.conf): keep it running and configured. With an exit node, route the group into it
# while that node is usable - online, still offered as an exit node, and selected - otherwise the group keeps
# the normal upstream (rule PREF). With a shared subnet, advertise it.
apply_instances() {
  IF=$(cfg IFACE)
  [ -s $ST/cfg/ts.conf ] || return 0
  while read -r x_g x_ip x_sn x_host x_rest; do
    case "$x_g" in ''|\#*) continue ;; esac
    x_net=$(group_field "$x_g" 4); [ -n "$x_net" ] || continue
    x_want=-; [ "$x_sn" = 1 ] && x_want=$x_net
    ts_lock "$x_g"
    if ! ts_net_up "$x_g" >/dev/null 2>&1; then
      x_bs=down; x_on=0; x_ok=0; x_cur=-
    else
      set -- $(ts_probe $x_g $x_net); x_bs=$1; x_on=$2; x_ok=$3; x_cur=$4
      ts_session "$x_g" "$x_host" "$x_bs"
      if [ "$x_bs" = Running ]; then
        x_ch=0
        if [ "$x_cur" != "$x_ip" ]; then ts_set_exit "$x_g" "$x_ip" && x_ch=1; fi
        if [ "$(cat $TSD/$x_g/subnet 2>/dev/null)" != "$x_want" ]; then ts_set_subnet "$x_g" "$(cat $TSD/$x_g/slot)" "$x_want" && x_ch=1; fi
        if [ $x_ch = 1 ]; then sleep 2; set -- $(ts_probe $x_g $x_net); x_bs=$1; x_on=$2; x_ok=$3; x_cur=$4; fi
      fi
    fi
    x_n=$(cat $TSD/$x_g/slot 2>/dev/null)
    echo "$x_bs" > $TSD/$x_g/bstate
    echo "$x_ok" > $TSD/$x_g/subnetok
    x_r=none
    if [ "$x_ip" != - ]; then
      x_r=fallback
      if [ "$x_bs" = Running ] && [ "$x_on" = 1 ] && [ "$x_cur" = "$x_ip" ]; then
        # Online alone isn't enough: the node may have stopped offering itself as an exit node (its 0/0 then goes
        # nowhere). It must still be listed.
        tsc $x_g exit-node list 2>/dev/null | grep -q " $x_ip " && x_r=active
      fi
    fi
    x_old=$(cat $TSD/$x_g/route 2>/dev/null)
    if [ $x_r = active ]; then
      echo "$x_ip" > $TSD/$x_g/routeip
      ip rule | grep -qE "^$EXIT_PREF:.*from $x_net iif $IF lookup $((TS_TABLE + x_n))( |\$)" \
        || ip rule add pref $EXIT_PREF from $x_net iif $IF lookup $((TS_TABLE + x_n))
    else
      while ip rule del pref $EXIT_PREF from $x_net iif $IF 2>/dev/null; do :; done
      # Flows NATed onto the link would now leave the upstream with a 198.18 source: drop them.
      [ "$x_old" = active ] && [ -n "$x_n" ] && [ -d /sys/class/net/sg$x_n ] && ts_relink $x_n
    fi
    ts_unlock "$x_g"
    if [ "$x_r" != "$x_old" ]; then
      echo $x_r > $TSD/$x_g/route
      [ $x_r != none ] && log "exit $x_g: $x_r (node $x_ip, tailscale ${x_bs:-down})"
    fi
  done < $ST/cfg/ts.conf
}

# ts_info <group>: instance state for the app: TSSTATE, TSAUTH (login URL) and the full status JSON.
ts_info() {
  tn_n=$(cat $TSD/$1/slot 2>/dev/null)
  if [ -n "$tn_n" ] && ts_ready $1 $tn_n; then
    ts_uplink $tn_n
    [ $? = 2 ] && { log "$1: upstream changed - restarting its Tailscale"; ts_daemon_kill $1; }
  fi
  if ! ts_daemon_ok $1; then echo "TSSTATE=down"; return 0; fi
  tn_j=$TSD/$1/status.$$
  tsc $1 status --json > $tn_j 2>/dev/null
  tn_bs=$(sed -n 's/.*"BackendState": "\([A-Za-z]*\)".*/\1/p' $tn_j | head -1)
  echo "TSSTATE=${tn_bs:-starting}"
  tn_u=$(sed -n 's/.*"AuthURL": "\(https:[^"]*\)".*/\1/p' $tn_j | head -1)
  [ -z "$tn_u" ] && tn_u=$(sed -n 's/.*"AuthURL": "\(https:[^"]*\)".*/\1/p' $TSD/$1/up.json 2>/dev/null | head -1)
  # NeedsMachineAuth: the link was used already; an admin has to approve the device.
  [ "$tn_bs" = NeedsLogin ] && [ -n "$tn_u" ] && echo "TSAUTH=$tn_u"
  echo "JSON_BEGIN"; cat $tn_j; echo; echo "JSON_END"
  rm -f $tn_j
}

# ts_install <package.tgz> <sha256>: the official static linux/arm64 package -> $TSD/bin.
ts_install() {
  [ -f "$1" ] || { echo "ERROR: $1 not found"; return 1; }
  [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ] || { echo "ERROR: checksum mismatch"; return 1; }
  rm -rf $TSD/tmp; mkdir -p $TSD/tmp $TSD/bin; chmod 700 $TSD
  tar -xzf "$1" -C $TSD/tmp || { rm -rf $TSD/tmp; echo "ERROR: could not unpack the package"; return 1; }
  for in_b in tailscaled tailscale; do
    in_f=$(find $TSD/tmp -type f -name $in_b | head -1)
    [ -n "$in_f" ] || { rm -rf $TSD/tmp; echo "ERROR: $in_b is missing from the package"; return 1; }
    chown 0:0 "$in_f"; chmod 700 "$in_f"
    mv "$in_f" $TSD/bin/$in_b.new && mv $TSD/bin/$in_b.new $TSD/bin/$in_b
  done
  rm -rf $TSD/tmp
  in_v=$($TSD/bin/tailscaled --version 2>/dev/null | head -1)
  [ -n "$in_v" ] || { echo "ERROR: tailscaled does not run on this phone"; return 1; }
  echo "$in_v" > $TSD/bin/version
  echo "OK $in_v"
}

# ts_logout <group>: sign the instance out of the tailnet and forget it.
ts_logout() {
  if [ -f $TSD/$1/slot ] && ts_bin_ok; then
    ts_daemon_ok $1 || ts_net_up $1 >/dev/null 2>&1
    tsc $1 logout >/dev/null 2>&1
  fi
  ts_down $1
  rm -rf $TSD/$1
  ts_idle
  echo "OK"
}

# ts_gc <group ids the app knows, comma-separated>: instances nobody needs. The app runs this once when it
# starts: an editor that was open when the app died couldn't clean up. Instances of networks the app doesn't know
# (a new network that was never saved) are signed out of the tailnet; the others are stopped - while the hotspot
# runs only those of groups without an instance.
ts_gc() {
  if [ -n "$1" ]; then
    for gc_d in $TSD/*/; do
      gc_g=$(basename "$gc_d"); [ -f "$gc_d/slot" ] || continue
      case ",$1," in
        *",$gc_g,"*) ;;
        *) ts_lock "$gc_g"; ts_logout "$gc_g" >/dev/null; log "signed out the Tailscale device of unknown network $gc_g" ;;
      esac
    done
  fi
  if [ "$(cat $ST/state 2>/dev/null)" = running ]; then ts_down_unused; ts_idle; else ts_down_all; fi
  echo "OK"
}

# ---------------------------------------------------------------- firewall

fw_teardown() {
  while $IPT -D INPUT -j mh_IN 2>/dev/null; do :; done
  while $IPT -D FORWARD -j mh_FWD 2>/dev/null; do :; done
  while $IPT -t nat -D POSTROUTING -j mh_NAT 2>/dev/null; do :; done
  while $IPT -t mangle -D FORWARD -j mh_MSS 2>/dev/null; do :; done
  for c in mh_IN mh_FWD mh_SRC mh_XG; do $IPT -F $c 2>/dev/null; $IPT -X $c 2>/dev/null; done
  $IPT -t nat -F mh_NAT 2>/dev/null; $IPT -t nat -X mh_NAT 2>/dev/null
  $IPT -t mangle -F mh_MSS 2>/dev/null; $IPT -t mangle -X mh_MSS 2>/dev/null
}

fw_setup() {
  IF=$1
  fw_teardown
  $IPT -N mh_IN; $IPT -N mh_FWD; $IPT -N mh_SRC; $IPT -N mh_XG
  $IPT -t nat -N mh_NAT; $IPT -t mangle -N mh_MSS

  # Traffic to the phone itself: DHCP always, ping from valid clients, nothing else.
  $IPT -A mh_IN -i $IF -p udp --dport 67 -j ACCEPT
  $IPT -A mh_IN -i $IF -j mh_SRC
  $IPT -A mh_IN -i $IF -p icmp -j ACCEPT
  $IPT -A mh_IN -i $IF -m state --state ESTABLISHED,RELATED -j ACCEPT
  $IPT -A mh_IN -i $IF -j DROP

  # Forwarding: anti-spoof first; device-to-device traffic is decided entirely in mh_XG; then replies from
  # outside (upstream/Tailscale); then per-group policy.
  $IPT -A mh_FWD -i $IF -j mh_SRC
  $IPT -A mh_FWD -i $IF -o $IF -j mh_XG
  $IPT -A mh_FWD ! -i $IF -o $IF -m state --state ESTABLISHED,RELATED -j ACCEPT
  while read -r id gw pfx net mask inet iso ds de lt dns ts reach rest; do
    case "$id" in ''|\#*) continue ;; esac
    [ "$iso" = 0 ] && $IPT -A mh_XG -s $net -d $net -j ACCEPT
    # Access to other groups (one-way). Replies are allowed only for pairs allowed *now*, so removing access
    # also cuts connections that were already open.
    for f_t in $(echo "${reach:--}" | tr ',' ' '); do
      [ "$f_t" = "-" ] && continue
      f_tnet=$(group_field "$f_t" 4)
      if [ -n "$f_tnet" ] && [ "$f_tnet" != "$net" ]; then
        $IPT -A mh_XG -s $net -d $f_tnet -j ACCEPT
        $IPT -A mh_XG -s $f_tnet -d $net -m state --state ESTABLISHED,RELATED -j ACCEPT
      fi
    done
    # Tailscale (tun*): only groups that have it enabled, regardless of their internet setting.
    if [ "$ts" = 1 ]; then
      $IPT -A mh_FWD -i $IF -o tun+ -s $net -j ACCEPT
    else
      $IPT -A mh_FWD -i $IF -o tun+ -s $net -j DROP
    fi
    [ "$inet" = 1 ] && $IPT -A mh_FWD -i $IF ! -o $IF -s $net -j ACCEPT
    if [ "$inet" = 1 ] || [ "$ts" = 1 ]; then
      $IPT -t nat -A mh_NAT -s $net ! -o $IF -j MASQUERADE
    fi
  done < $ST/cfg/groups.conf
  # Groups shared with the tailnet: their instance (NATing the tailnet's connections to its end of the link) may
  # open connections to the group's devices; the devices' replies go back to it even without internet access.
  if [ -s $ST/cfg/ts.conf ]; then
    while read -r f_g f_ip f_sn f_rest; do
      case "$f_g" in ''|\#*) continue ;; esac
      [ "$f_sn" = 1 ] || continue
      f_net=$(group_field "$f_g" 4); [ -n "$f_net" ] || continue
      mkdir -p $TSD/$f_g; f_n=$(ts_slot "$f_g") || continue
      $IPT -A mh_FWD -i sg$f_n -o $IF -s 198.18.$f_n.2 -d $f_net -j ACCEPT
      $IPT -A mh_FWD -i $IF -o sg$f_n -s $f_net -d 198.18.$f_n.2 -m state --state ESTABLISHED,RELATED -j ACCEPT
    done < $ST/cfg/ts.conf
  fi
  $IPT -A mh_FWD -i $IF -j DROP
  $IPT -A mh_FWD -o $IF -j DROP
  $IPT -A mh_XG -j DROP

  # Unknown MAC or address outside its group's subnet: drop. Per-client RETURN rules are inserted on connect.
  $IPT -A mh_SRC -j DROP

  # Upstream MTU is often < 1500 (mobile data): clamp TCP MSS.
  $IPT -t mangle -A mh_MSS -i $IF -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
  $IPT -t mangle -A mh_MSS -o $IF -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

  $IPT -I INPUT 1 -j mh_IN
  $IPT -I FORWARD 1 -j mh_FWD
  $IPT -t nat -I POSTROUTING 1 -j mh_NAT
  $IPT -t mangle -I FORWARD 1 -j mh_MSS
}

fw_hooks_ok() {
  $IPT -C INPUT -j mh_IN 2>/dev/null && $IPT -C FORWARD -j mh_FWD 2>/dev/null \
    && $IPT -t nat -C POSTROUTING -j mh_NAT 2>/dev/null
}

src_rule_del() {
  $IPT -S mh_SRC 2>/dev/null | grep -i -- "--mac-source $1 " | sed 's/^-A /-D /' | while read -r s_r; do
    $IPT $s_r
  done
}

# Allow <mac> to send only from <group>'s subnet.
src_rule() {
  src_rule_del "$1"
  s_net=$(group_field "$2" 4)
  [ -n "$s_net" ] && $IPT -I mh_SRC 1 -s "$s_net" -m mac --mac-source "$1" -j RETURN
}

# ---------------------------------------------------------------- ARP pinning
# Clients can't spoof IPv4 sources (mh_SRC), but ARP isn't filtered: a guest could claim a home device's IP
# in an ARP request and the phone would then send that device's traffic to the guest. Pin a permanent
# neighbour entry for every connected client's current lease; permanent entries ignore ARP updates.

pin_leases() {
  IF=$(cfg IFACE)
  : > $ST/pins.new
  while read -r p_mac p_grp p_since; do
    [ -z "$p_mac" ] && continue
    p_net=$(group_field "$p_grp" 4)
    for p_ip in $(awk -v m="$p_mac" 'tolower($2)==m {print $3}' $ST/dnsmasq.leases 2>/dev/null); do
      in_cidr "$p_ip" "$p_net" || continue
      echo "$p_mac $p_ip" >> $ST/pins.new
      ip neigh replace "$p_ip" lladdr "$p_mac" dev $IF nud permanent 2>/dev/null
      break
    done
  done < $ST/connected
  if [ -f $ST/pins ]; then
    while read -r o_mac o_ip; do
      grep -q "^$o_mac $o_ip\$" $ST/pins.new || ip neigh del "$o_ip" dev $IF 2>/dev/null
    done < $ST/pins
  fi
  mv $ST/pins.new $ST/pins
}

unpin_mac() {
  [ -f $ST/pins ] || return
  grep "^$1 " $ST/pins | while read -r u_mac u_ip; do ip neigh del "$u_ip" dev $(cfg IFACE) 2>/dev/null; done
  grep -v "^$1 " $ST/pins > $ST/pins.tmp; mv $ST/pins.tmp $ST/pins
}

# ---------------------------------------------------------------- dhcp

gen_dnsmasq() {
  IF=$1
  {
    echo "port=0"
    echo "interface=$IF"
    echo "bind-interfaces"
    echo "dhcp-authoritative"
    echo "dhcp-leasefile=$ST/dnsmasq.leases"
    echo "dhcp-hostsfile=$ST/hosts.dhcp"
    echo "dhcp-ignore=#known"
    echo "pid-file=$ST/dnsmasq.pid"
    echo "user=root"
    # Name every column: in sh the last variable takes the rest of the line, which would leak into "dns".
    while read -r id gw pfx net mask inet iso ds de lt dns ts reach rest; do
      case "$id" in ''|\#*) continue ;; esac
      echo "dhcp-range=net:$id,$ds,$de,$mask,$lt"
      echo "dhcp-option=net:$id,3,$gw"
      # Only well-formed IPv4 addresses: clients drop DHCP replies with a malformed DNS option.
      d_dns=$(echo "$dns" | awk -F, '{ o = ""; for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/) o = o (o == "" ? "" : ",") $i; print o }')
      [ -n "$d_dns" ] && echo "dhcp-option=net:$id,6,$d_dns"
    done < $ST/cfg/groups.conf
  } > $ST/dnsmasq.conf
}

# hosts.dhcp: every connected client gets its group's tag (plus a fixed IP if configured).
# Unknown MACs get no DHCP at all (dhcp-ignore=#known).
gen_hosts() {
  : > $ST/hosts.tmp
  while read -r h_mac h_grp; do
    [ -z "$h_mac" ] && continue
    h_ip=$(awk -v m="$h_mac" -v g="$h_grp" 'tolower($1)==m && $2==g {print $3; exit}' $ST/cfg/static.conf 2>/dev/null)
    if [ -n "$h_ip" ] && [ "$h_ip" != "-" ]; then
      echo "$h_mac,net:$h_grp,$h_ip" >> $ST/hosts.tmp
    else
      echo "$h_mac,net:$h_grp" >> $ST/hosts.tmp
    fi
  done < $ST/map
  mv $ST/hosts.tmp $ST/hosts.dhcp
  h_p=$(dnsmasq_pid)
  [ -n "$h_p" ] && kill -HUP "$h_p"
}

start_dnsmasq() {
  # dnsmasq daemonizes; never capture its output with $(...) or the shell waits on the pipe forever.
  $DNSMASQ -C $ST/dnsmasq.conf </dev/null >$ST/dnsmasq.out 2>&1
  sleep 1
  if [ -z "$(dnsmasq_pid)" ]; then
    log "dnsmasq failed: $(head -c 300 $ST/dnsmasq.out)"
    return 1
  fi
}

# ---------------------------------------------------------------- events

on_connect() {
  mac=""; kid=""; next=0
  for w in $1; do
    [ $next = 1 ] && { mac=$w; next=0; }
    [ "$w" = AP-STA-CONNECTED ] && next=1
    case "$w" in keyid=*) kid=${w#keyid=} ;; esac
  done
  is_mac "$mac" || return
  mac=$(echo "$mac" | tr 'A-F' 'a-f')
  case "$kid" in *[!a-z0-9_]*) kid="" ;; esac
  group_exists "$kid" && grp=$kid || grp=$(cfg DEFAULT_GROUP)

  grep -v "^$mac " $ST/map > $ST/map.tmp 2>/dev/null; echo "$mac $grp" >> $ST/map.tmp; mv $ST/map.tmp $ST/map
  grep -v "^$mac " $ST/connected > $ST/conn.tmp 2>/dev/null; echo "$mac $grp $(date +%s)" >> $ST/conn.tmp; mv $ST/conn.tmp $ST/connected
  unpin_mac "$mac"
  src_rule "$mac" "$grp"
  gen_hosts
  log "connect $mac group=$grp"
}

# Forget everything about a client when it leaves, so a device cloning its MAC can't inherit its group.
on_disconnect() {
  mac=""; next=0
  for w in $1; do
    [ $next = 1 ] && { mac=$w; next=0; }
    [ "$w" = AP-STA-DISCONNECTED ] && next=1
  done
  is_mac "$mac" || return
  mac=$(echo "$mac" | tr 'A-F' 'a-f')
  grep -v "^$mac " $ST/connected > $ST/conn.tmp 2>/dev/null; mv $ST/conn.tmp $ST/connected
  grep -v "^$mac " $ST/map > $ST/map.tmp 2>/dev/null; mv $ST/map.tmp $ST/map
  src_rule_del "$mac"
  unpin_mac "$mac"
  gen_hosts
  log "disconnect $mac"
}

# Undo every network change. "soft": leave the interface itself alone (another hostapd may own it now).
net_teardown() {
  t_if=$(cfg IFACE)
  t_p=$(dnsmasq_pid); [ -n "$t_p" ] && kill "$t_p" 2>/dev/null
  pkill -f "dnsmasq -C $ST/dnsmasq[.]conf" 2>/dev/null
  rm -f $ST/dnsmasq.pid

  fw_teardown
  while ip rule del pref $PREF 2>/dev/null; do :; done
  while ip rule del pref $TS_PREF 2>/dev/null; do :; done
  while ip rule del pref $EXIT_PREF 2>/dev/null; do :; done
  while ip rule del pref $LOCAL_PREF 2>/dev/null; do :; done
  : > $ST/tailscale 2>/dev/null
  # (Route state files stay: a restart re-routes exit groups whose instance is still active, see ts_resume_exits.)

  if [ -n "$t_if" ] && [ -f $ST/cfg/groups.conf ]; then
    while read -r id gw pfx net rest; do
      case "$id" in ''|\#*) continue ;; esac
      ip route del $net dev $t_if table local_network 2>/dev/null
      ip addr del $gw/$pfx dev $t_if 2>/dev/null
    done < $ST/cfg/groups.conf
    ip neigh flush dev $t_if nud permanent 2>/dev/null
    rm -f $ST/pins
    echo 0 > /proc/sys/net/ipv4/conf/$t_if/proxy_arp 2>/dev/null
    echo 0 > /proc/sys/net/ipv4/conf/$t_if/proxy_arp_pvlan 2>/dev/null
    if [ -f $ST/disable_ipv6.orig ]; then
      cat $ST/disable_ipv6.orig > /proc/sys/net/ipv6/conf/$t_if/disable_ipv6 2>/dev/null
      rm -f $ST/disable_ipv6.orig
    fi
    if [ "$1" != soft ]; then
      ip addr flush dev $t_if 2>/dev/null
      ip link set $t_if down 2>/dev/null
      rm -f $CTRL_DIR/$t_if
    fi
  fi

  # Leave forwarding on if another tethering needs it; netd turns it off itself later.
  if [ -f $ST/ip_forward.orig ]; then
    if [ "$(cat $ST/ip_forward.orig)" = 1 ] || ! other_tethering_active; then
      cat $ST/ip_forward.orig > /proc/sys/net/ipv4/ip_forward
    fi
    rm -f $ST/ip_forward.orig
  fi
  [ -f $ST/send_redirects.orig ] && cat $ST/send_redirects.orig > /proc/sys/net/ipv4/conf/all/send_redirects && rm -f $ST/send_redirects.orig
  : > $ST/connected 2>/dev/null; : > $ST/map 2>/dev/null; : > $ST/upstream 2>/dev/null
}

restore_wifi() {
  if [ -f $ST/wifi_was_on ]; then
    rm -f $ST/wifi_was_on
    cmd wifi set-wifi-enabled enabled
  fi
}

watchdog() {
  IF=$(cfg IFACE)
  while true; do
    sleep 5
    [ "$(cat $ST/state 2>/dev/null)" = running ] || continue
    if [ -z "$(hostapd_pid)" ]; then
      if system_hotspot_recent; then
        w_reason="The phone's built-in hotspot (or a Samsung feature using it, e.g. Quick Share / Smart View) took over the Wi-Fi radio. Turn it off, then start again."
      else
        w_reason="The access point stopped unexpectedly (driver or radio reset). Start again; see Log for details."
      fi
      log "hostapd died - cleaning up: $w_reason"
      # Clean up once (firewall, DHCP, routes, Wi-Fi-off) and leave the radio to whoever has it now.
      net_teardown soft
      restore_wifi
      echo failed > $ST/state
      echo "$w_reason" > $ST/reason
      ts_down_all
      w_p=$(cat $ST/daemon.pid 2>/dev/null); pid_ok "$w_p" mh_engine && kill "$w_p" 2>/dev/null
      pkill -f "logcat -T 1 -b main -s hostap[d]" 2>/dev/null
      rm -f $ST/daemon.pid $ST/watchdog.pid
      exit 0
    fi
    if [ -z "$(dnsmasq_pid)" ]; then
      log "dnsmasq died - restarting"
      start_dnsmasq
    fi
    apply_upstream
    apply_tailscale
    apply_instances
    [ "$(cat /proc/sys/net/ipv4/ip_forward)" = 1 ] || { echo 1 > /proc/sys/net/ipv4/ip_forward; log "ip_forward re-enabled"; }
    if ! fw_hooks_ok; then
      log "firewall hooks missing - rebuilding"
      fw_setup "$IF"
      while read -r w_m w_g w_s; do [ -n "$w_m" ] && src_rule "$w_m" "$w_g"; done < $ST/connected
    fi
    pin_leases
  done
}

daemon() {
  IF=$(cfg IFACE)
  echo $$ > $ST/daemon.pid
  watchdog &
  echo $! > $ST/watchdog.pid
  while true; do
    logcat -T 1 -b main -s hostapd:I 2>/dev/null | while read -r line; do
      case "$line" in
        *"$IF: AP-STA-CONNECTED "*) on_connect "$line" ;;
        *"$IF: AP-STA-DISCONNECTED "*) on_disconnect "$line" ;;
      esac
    done
    sleep 1
  done
}

kill_engine_procs() {
  for f in watchdog daemon; do
    k_p=$(cat $ST/$f.pid 2>/dev/null); pid_ok "$k_p" mh_engine && kill "$k_p" 2>/dev/null
    rm -f $ST/$f.pid
  done
  pkill -f "mh_engine[.]sh daemon" 2>/dev/null
  pkill -f "logcat -T 1 -b main -s hostap[d]" 2>/dev/null
  pkill -f "hostapd $HD/hostapd[.]conf" 2>/dev/null
  # A killed watchdog may have held instance locks; nobody else holds them now (the app's calls are serialized).
  rm -rf $TSD/*/.lock 2>/dev/null
}

# ---------------------------------------------------------------- start / stop

start() {
  CFG=$1
  [ -f "$CFG/engine.conf" ] && [ -f "$CFG/hostapd.conf" ] && [ -f "$CFG/groups.conf" ] || { echo "ERROR: bad config dir"; exit 1; }
  # Restart: undo the previous run but don't toggle Wi-Fi back on (handled below).
  stop keepwifi >/dev/null 2>&1

  mkdir -p $ST $HD; chmod 700 $ST
  rm -rf $ST/cfg; mkdir $ST/cfg; cp "$CFG"/* $ST/cfg/
  [ "$(readlink -f "$0")" != "$ST/mh_engine.sh" ] && cp "$0" $ST/mh_engine.sh
  : > $ST/engine.log; : > $ST/map; : > $ST/connected; : > $ST/hosts.dhcp; : > $ST/upstream
  touch $ST/cfg/static.conf
  cat /proc/sys/kernel/random/boot_id > $ST/boot_id
  echo starting > $ST/state; rm -f $ST/reason

  IF=$(cfg IFACE); MAC=$(cfg MAC); WM=$(cfg WIFI_MODE)
  [ -d /sys/class/net/$IF ] || { fail "interface $IF not found"; exit 1; }
  log "start iface=$IF wifi_mode=$WM"

  cp $ST/cfg/hostapd.conf $HD/hostapd.conf
  cp $ST/cfg/psk.txt $HD/psk.txt
  if [ -f $ST/cfg/deny.txt ]; then cp $ST/cfg/deny.txt $HD/deny.txt; else rm -f $HD/deny.txt; fi
  chown -R wifi:wifi $HD; chmod 770 $HD; chmod 660 $HD/*; restorecon -R $HD

  cmd wifi stop-softap >/dev/null 2>&1
  if [ "$WM" = off ]; then
    if [ ! -f $ST/wifi_was_on ] && wifi_is_on; then
      touch $ST/wifi_was_on
      cmd wifi set-wifi-enabled disabled
      n=0; while [ $n -lt 8 ] && cmd wifi status 2>/dev/null | grep -q "Wifi is enabled"; do sleep 1; n=$((n+1)); done
      sleep 1
    fi
  elif [ -f $ST/wifi_was_on ]; then
    restore_wifi
  fi

  cat /proc/sys/net/ipv4/ip_forward > $ST/ip_forward.orig
  cat /proc/sys/net/ipv4/conf/all/send_redirects > $ST/send_redirects.orig

  # Firewall and event loop must be in place before the AP accepts its first client.
  fw_setup $IF
  setsid sh $ST/mh_engine.sh daemon </dev/null >/dev/null 2>&1 &

  ip link set $IF down
  [ -n "$MAC" ] && ip link set $IF address $MAC
  rm -f $CTRL_DIR/$IF
  setsid $HOSTAPD $HD/hostapd.conf </dev/null >/dev/null 2>&1 &

  n=0; up=0
  while [ $n -lt 15 ]; do
    sleep 1; n=$((n+1))
    [ -n "$(hostapd_pid)" ] || break
    [ "$(cat /sys/class/net/$IF/operstate 2>/dev/null)" = up ] && { up=1; break; }
  done
  if [ $up != 1 ]; then
    stop >/dev/null 2>&1
    fail "access point did not start (the Wi-Fi driver refused the settings - see the radio log)"
    exit 1
  fi
  log "AP up after ${n}s"

  # IPv4 only: the firewall is iptables (not ip6tables), so don't let clients reach the phone over IPv6 link-local.
  cat /proc/sys/net/ipv6/conf/$IF/disable_ipv6 > $ST/disable_ipv6.orig 2>/dev/null
  echo 1 > /proc/sys/net/ipv6/conf/$IF/disable_ipv6

  ip addr flush dev $IF
  while read -r id gw pfx net rest; do
    case "$id" in ''|\#*) continue ;; esac
    ip addr add $gw/$pfx dev $IF
    ip route replace $net dev $IF table local_network src $gw
    # Sockets of apps bound to a network (e.g. Tailscale's, pinned to Wi-Fi/mobile) would otherwise send
    # traffic for hotspot clients out to the upstream router. This lets Tailscale on the phone talk to
    # Tailscale on hotspot clients directly instead of through a DERP relay (~150 ms -> a few ms).
    ip rule add pref $LOCAL_PREF to $net lookup local_network
  done < $ST/cfg/groups.conf
  ip link set $IF up
  # Proxy ARP makes the phone answer for every address on the interface so same-group devices can be
  # routed through it (ap_isolate blocks direct traffic). Only needed if some group allows device-to-device.
  if awk '$1 !~ /^#/ && $7 == "0" {f=1} END {exit !f}' $ST/cfg/groups.conf; then
    echo 1 > /proc/sys/net/ipv4/conf/$IF/proxy_arp
    echo 1 > /proc/sys/net/ipv4/conf/$IF/proxy_arp_pvlan
  fi
  echo 0 > /proc/sys/net/ipv4/conf/$IF/send_redirects
  echo 0 > /proc/sys/net/ipv4/conf/all/send_redirects
  echo 1 > /proc/sys/net/ipv4/ip_forward

  # Exit groups whose instance survived the restart (Apply) go straight back into it, before any device can use
  # the normal upstream rule set up next.
  ts_resume_exits
  apply_upstream || log "no upstream yet - will retry"
  apply_tailscale
  # Tailscale instances: drop the ones no group uses any more; the watchdog starts the others and routes their
  # groups into them once their exit node is reachable (until then those groups use the normal upstream).
  ts_down_unused
  [ -s $ST/cfg/ts.conf ] && ts_fw

  gen_dnsmasq $IF
  if ! start_dnsmasq; then
    stop >/dev/null 2>&1
    fail "DHCP server failed to start"
    exit 1
  fi

  echo running > $ST/state
  log "running"
  echo "OK"
}

# stop [keepwifi]
stop() {
  reset_stale >/dev/null
  kill_engine_procs
  net_teardown
  [ "$1" = keepwifi ] || restore_wifi
  echo stopped > $ST/state; rm -f $ST/reason $ST/boot_id
  # A restart (start runs "stop keepwifi") keeps the exit-node instances: the new run reuses them.
  [ "$1" = keepwifi ] || ts_down_all
  log "stopped"
  echo "OK"
}

status() {
  reset_stale >/dev/null
  s_state=$(cat $ST/state 2>/dev/null || echo stopped)
  s_hp=0; [ -n "$(hostapd_pid)" ] && s_hp=1
  s_dp=0; [ -n "$(dnsmasq_pid)" ] && s_dp=1
  s_da=0; daemon_alive && s_da=1
  s_reason=$(head -n 1 $ST/reason 2>/dev/null)
  if [ "$s_state" = running ] && { [ $s_hp = 0 ] || [ $s_da = 0 ]; }; then
    s_state=failed
    [ -n "$s_reason" ] || s_reason="The hotspot processes are no longer running."
  fi
  echo "STATE=$s_state"
  [ -n "$s_reason" ] && echo "REASON=$s_reason"
  echo "IFACE=$(cfg IFACE)"
  echo "UPSTREAM=$(cat $ST/upstream 2>/dev/null)"
  echo "TAILSCALE=$(cut -d' ' -f1 $ST/tailscale 2>/dev/null)"
  echo "TSBIN=$(cat $TSD/bin/version 2>/dev/null)"
  # Fingerprint of the config the engine runs (files in name order), so the app can tell which settings are live.
  [ "$s_state" = running ] && echo "CFGSUM=$(cat $ST/cfg/* 2>/dev/null | md5sum | cut -d' ' -f1)"
  # As of the watchdog's last pass:
  #   EXIT <group> <active|fallback> <tailscale state|-> <exit node ip>
  #   SUBNET <group> <subnet> <route approved 0|1> <tailscale state|->
  if [ "$s_state" = running ] && [ -s $ST/cfg/ts.conf ]; then
    while read -r x_g x_ip x_sn x_rest; do
      case "$x_g" in ''|\#*) continue ;; esac
      x_bs=$(cat $TSD/$x_g/bstate 2>/dev/null || echo -)
      [ "$x_ip" != - ] && echo "EXIT $x_g $(cat $TSD/$x_g/route 2>/dev/null || echo fallback) $x_bs $x_ip"
      [ "$x_sn" = 1 ] && echo "SUBNET $x_g $(group_field "$x_g" 4) $(cat $TSD/$x_g/subnetok 2>/dev/null || echo 0) $x_bs"
    done < $ST/cfg/ts.conf
  fi
  echo "HOSTAPD=$s_hp"
  echo "DHCP=$s_dp"
  echo "DAEMON=$s_da"
  # Per-call temp files: a manual "status" may run while the app polls.
  s_bt=$ST/bytes.$$; s_st=$ST/sta.$$
  # Bytes sent by each client (counter of its anti-spoof rule).
  # iptables prints the match as "MACaa:bb:..." (some builds: "MAC aa:bb:...").
  $IPT -L mh_SRC -v -x -n 2>/dev/null | awk '$3=="RETURN" {for(i=1;i<=NF;i++) if($i ~ /^MAC/) {m=substr($i,4); if(m=="") m=$(i+1); print tolower(m), $2}}' > $s_bt
  # Radio stats (RSSI, byte counters, link rates) from the driver via the native helper, if present.
  s_wl=${1:-$(cfg WL_HELPER)}   # the app passes the current path (it moves when the app is updated)
  : > $s_st
  if [ -n "$s_wl" ] && [ -x "$s_wl" ] && [ -s $ST/connected ]; then
    "$s_wl" "$(cfg IFACE)" $(awk '{print $1}' $ST/connected) > $s_st 2>/dev/null
  fi
  while read -r c_mac c_grp c_since; do
    [ -z "$c_mac" ] && continue
    c_net=$(group_field "$c_grp" 4)
    c_ip=""; c_host=""
    # The lease file can still hold an old lease from another group: take the one inside this group's subnet.
    while read -r l_exp l_mac l_ip l_host l_rest; do
      [ "$(echo "$l_mac" | tr 'A-F' 'a-f')" = "$c_mac" ] || continue
      in_cidr "$l_ip" "$c_net" || continue
      c_ip=$l_ip; c_host=$l_host; break
    done < $ST/dnsmasq.leases 2>/dev/null
    # STA <mac> <rssi> <tx_bytes=device download> <rx_bytes=device upload> <tx_kbps> <rx_kbps> <idle>
    c_sta=$(grep "^STA $c_mac " $s_st 2>/dev/null | head -1)
    if [ -n "$c_sta" ]; then
      set -- $c_sta
      c_rssi=$3; c_down=$4; c_up=$5; c_txr=$6; c_rxr=$7
    else
      c_rssi=-; c_down=-; c_txr=-; c_rxr=-
      c_up=$(awk -v m="$c_mac" '$1==m {print $2; exit}' $s_bt)
    fi
    # CLIENT <mac> <group> <ip> <host> <since> <up_bytes> <down_bytes> <rssi_dbm> <tx_kbps> <rx_kbps>
    echo "CLIENT $c_mac $c_grp ${c_ip:--} ${c_host:--} $c_since ${c_up:-0} $c_down $c_rssi $c_txr $c_rxr"
  done < $ST/connected
  rm -f $s_bt $s_st
  echo "LOG_BEGIN"
  tail -n 15 $ST/engine.log 2>/dev/null
  echo "LOG_END"
}

case "$1" in
  start)  start "$2" ;;
  stop)   stop ;;
  status) status "$2" ;;
  ts-install) ts_install "$2" "$3" ;;
  ts-up)      ts_lock "$2"; ts_up "$2" "$3"; ts_info "$2"; ts_unlock "$2" ;;
  ts-status)  ts_lock "$2"; ts_info "$2"; ts_unlock "$2" ;;
  ts-down)    ts_lock "$2"; ts_down "$2"; ts_unlock "$2"; ts_idle; echo "OK" ;;
  ts-logout)  ts_lock "$2"; ts_logout "$2" ;;
  ts-gc)      ts_gc "$2" ;;
  daemon) daemon ;;
  *) echo "usage: $0 start <cfgdir>|stop|status|ts-install|ts-up|ts-status|ts-down|ts-logout|ts-gc"; exit 2 ;;
esac
