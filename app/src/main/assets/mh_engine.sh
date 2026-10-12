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
#
# <cfgdir> must contain:
#   engine.conf  KEY=VALUE lines: IFACE, MAC, WIFI_MODE (keep|off), UPSTREAM (auto|<iface>), DEFAULT_GROUP, WL_HELPER
#   hostapd.conf complete hostapd config (wpa_psk_file must point to $HD/psk.txt)
#   psk.txt      hostapd wpa_psk_file, one "keyid=<group> 00:00:00:00:00:00 <passphrase>" per group
#   groups.conf  ID GW PREFIX NETCIDR NETMASK INTERNET(0|1) ISOLATE(0|1) DHCP_START DHCP_END LEASE DNS(csv) TAILSCALE(0|1) REACH(csv ids|-)
#   static.conf  MAC GROUP IP   (optional fixed addresses)
#   deny.txt     blocked MACs   (optional)
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
pid_ok() { [ -n "$1" ] && [ -r "/proc/$1/cmdline" ] && tr '\0' ' ' < "/proc/$1/cmdline" | grep -q -- "$2"; }

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
    ip route get 8.8.8.8 2>/dev/null | sed -n 's/.* table \([^ ]*\).*/\1/p' | head -1
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

tailscale_iface() {
  ip -4 -o addr show 2>/dev/null | awk '{ split($4, a, "/"); split(a[1], o, ".");
    if (o[1] == 100 && o[2] >= 64 && o[2] <= 127) { print $2; exit } }'
}

apply_tailscale() {
  IF=$(cfg IFACE)
  a_ts=$(tailscale_iface)
  a_want=$(awk '$1 !~ /^#/ && $12 == "1" {print $4}' $ST/cfg/groups.conf)
  [ -z "$a_want" ] && a_ts=""
  if [ "$a_ts" = "$(cat $ST/tailscale 2>/dev/null)" ] && { [ -z "$a_ts" ] || ip rule | grep -q "^$TS_PREF:"; }; then
    return 0
  fi
  while ip rule del pref $TS_PREF 2>/dev/null; do :; done
  if [ -n "$a_ts" ]; then
    for a_net in $a_want; do ip rule add pref $TS_PREF from $a_net iif $IF lookup $a_ts; done
    log "tailscale -> $a_ts"
  elif [ -n "$a_want" ] && [ -s $ST/tailscale ]; then
    log "tailscale disconnected"
  fi
  echo "$a_ts" > $ST/tailscale
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
  while ip rule del pref $LOCAL_PREF 2>/dev/null; do :; done
  : > $ST/tailscale 2>/dev/null

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

  apply_upstream || log "no upstream yet - will retry"
  apply_tailscale

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
  echo "TAILSCALE=$(cat $ST/tailscale 2>/dev/null)"
  echo "HOSTAPD=$s_hp"
  echo "DHCP=$s_dp"
  echo "DAEMON=$s_da"
  # Bytes sent by each client (counter of its anti-spoof rule).
  # iptables prints the match as "MACaa:bb:..." (some builds: "MAC aa:bb:...").
  $IPT -L mh_SRC -v -x -n 2>/dev/null | awk '$3=="RETURN" {for(i=1;i<=NF;i++) if($i ~ /^MAC/) {m=substr($i,4); if(m=="") m=$(i+1); print tolower(m), $2}}' > $ST/bytes.tmp
  # Radio stats (RSSI, byte counters, link rates) from the driver via the native helper, if present.
  s_wl=${1:-$(cfg WL_HELPER)}   # the app passes the current path (it moves when the app is updated)
  : > $ST/sta.tmp
  if [ -n "$s_wl" ] && [ -x "$s_wl" ] && [ -s $ST/connected ]; then
    "$s_wl" "$(cfg IFACE)" $(awk '{print $1}' $ST/connected) > $ST/sta.tmp 2>/dev/null
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
    c_sta=$(grep "^STA $c_mac " $ST/sta.tmp 2>/dev/null | head -1)
    if [ -n "$c_sta" ]; then
      set -- $c_sta
      c_rssi=$3; c_down=$4; c_up=$5; c_txr=$6; c_rxr=$7
    else
      c_rssi=-; c_down=-; c_txr=-; c_rxr=-
      c_up=$(awk -v m="$c_mac" '$1==m {print $2; exit}' $ST/bytes.tmp)
    fi
    # CLIENT <mac> <group> <ip> <host> <since> <up_bytes> <down_bytes> <rssi_dbm> <tx_kbps> <rx_kbps>
    echo "CLIENT $c_mac $c_grp ${c_ip:--} ${c_host:--} $c_since ${c_up:-0} $c_down $c_rssi $c_txr $c_rxr"
  done < $ST/connected
  rm -f $ST/bytes.tmp $ST/sta.tmp
  echo "LOG_BEGIN"
  tail -n 15 $ST/engine.log 2>/dev/null
  echo "LOG_END"
}

case "$1" in
  start)  start "$2" ;;
  stop)   stop ;;
  status) status "$2" ;;
  daemon) daemon ;;
  *) echo "usage: $0 start <cfgdir>|stop|status"; exit 2 ;;
esac
