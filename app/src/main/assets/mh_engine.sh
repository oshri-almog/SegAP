#!/system/bin/sh
# MultiHotspot engine - runs as root (via su).
#
# One access point, several passwords. Each password (hostapd keyid) is a "group"
# with its own subnet, DHCP range, DNS and firewall policy.
#
# usage:
#   mh_engine.sh start <cfgdir>   start with config files from <cfgdir>
#   mh_engine.sh stop             stop and undo every change
#   mh_engine.sh status           print state and connected clients
#   mh_engine.sh daemon           (internal) event loop
#
# <cfgdir> must contain:
#   engine.conf  KEY=VALUE lines: IFACE, MAC, WIFI_MODE (keep|off), UPSTREAM (auto|<iface>), DEFAULT_GROUP
#   hostapd.conf complete hostapd config (wpa_psk_file must point to $HD/psk.txt)
#   psk.txt      hostapd wpa_psk_file, one "keyid=<group> 00:00:00:00:00:00 <passphrase>" per group
#   groups.conf  ID GW PREFIX NETCIDR NETMASK INTERNET(0|1) ISOLATE(0|1) DHCP_START DHCP_END LEASE DNS(csv)
#   static.conf  MAC GROUP IP   (optional fixed addresses)

ST=/data/adb/multihotspot
HD=/data/vendor/wifi/hostapd/mh
CTRL_DIR=/data/vendor/wifi/hostapd/ctrl
HOSTAPD=/vendor/bin/hw/hostapd
DNSMASQ=/system/bin/dnsmasq
PREF=21500
IPT="iptables -w"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $ST/engine.log; }
fail() { log "ERROR: $*"; echo "ERROR: $*"; echo failed > $ST/state; }

cfg() { sed -n "s/^$1=//p" $ST/cfg/engine.conf 2>/dev/null | head -1; }

is_mac() { echo "$1" | grep -qiE '^([0-9a-f]{2}:){5}[0-9a-f]{2}$'; }

alive() { [ -n "$1" ] && [ -d "/proc/$1" ]; }

hostapd_pid() { pgrep -f "hostapd $HD/hostapd[.]conf" | head -1; }

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

# ---------------------------------------------------------------- firewall

fw_teardown() {
  while $IPT -D INPUT -j mh_IN 2>/dev/null; do :; done
  while $IPT -D FORWARD -j mh_FWD 2>/dev/null; do :; done
  while $IPT -t nat -D POSTROUTING -j mh_NAT 2>/dev/null; do :; done
  while $IPT -t mangle -D FORWARD -j mh_MSS 2>/dev/null; do :; done
  for c in mh_IN mh_FWD mh_SRC; do $IPT -F $c 2>/dev/null; $IPT -X $c 2>/dev/null; done
  $IPT -t nat -F mh_NAT 2>/dev/null; $IPT -t nat -X mh_NAT 2>/dev/null
  $IPT -t mangle -F mh_MSS 2>/dev/null; $IPT -t mangle -X mh_MSS 2>/dev/null
}

fw_setup() {
  IF=$1
  fw_teardown
  $IPT -N mh_IN; $IPT -N mh_FWD; $IPT -N mh_SRC
  $IPT -t nat -N mh_NAT; $IPT -t mangle -N mh_MSS

  # Traffic to the phone itself: DHCP always, ping from valid clients, nothing else.
  $IPT -A mh_IN -i $IF -p udp --dport 67 -j ACCEPT
  $IPT -A mh_IN -i $IF -j mh_SRC
  $IPT -A mh_IN -i $IF -p icmp -j ACCEPT
  $IPT -A mh_IN -i $IF -m state --state ESTABLISHED,RELATED -j ACCEPT
  $IPT -A mh_IN -i $IF -j DROP

  # Forwarding: anti-spoof first, then replies, then per-group policy.
  $IPT -A mh_FWD -i $IF -j mh_SRC
  $IPT -A mh_FWD -o $IF -m state --state ESTABLISHED,RELATED -j ACCEPT
  while read -r id gw pfx net mask inet iso ds de lt dns; do
    case "$id" in ''|\#*) continue ;; esac
    [ "$iso" = 0 ] && $IPT -A mh_FWD -i $IF -o $IF -s $net -d $net -j ACCEPT
    if [ "$inet" = 1 ]; then
      $IPT -A mh_FWD -i $IF ! -o $IF -s $net -j ACCEPT
      $IPT -t nat -A mh_NAT -s $net ! -o $IF -j MASQUERADE
    fi
  done < $ST/cfg/groups.conf
  $IPT -A mh_FWD -i $IF -j DROP
  $IPT -A mh_FWD -o $IF -j DROP

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

# Allow <mac> to send only from <group>'s subnet.
src_rule() {
  mac=$1; grp=$2
  $IPT -S mh_SRC 2>/dev/null | grep -i -- "--mac-source $mac " | sed 's/^-A /-D /' | while read -r r; do
    $IPT $r
  done
  net=$(group_field "$grp" 4)
  [ -n "$net" ] && $IPT -I mh_SRC 1 -s "$net" -m mac --mac-source "$mac" -j RETURN
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
    while read -r id gw pfx net mask inet iso ds de lt dns; do
      case "$id" in ''|\#*) continue ;; esac
      echo "dhcp-range=net:$id,$ds,$de,$mask,$lt"
      echo "dhcp-option=net:$id,3,$gw"
      echo "dhcp-option=net:$id,6,$dns"
    done < $ST/cfg/groups.conf
  } > $ST/dnsmasq.conf
}

# hosts.dhcp: every mapped client gets its group's tag (plus a fixed IP if configured).
gen_hosts() {
  # (shell variables are global: use h_* names so callers' mac/grp survive)
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
  P=$(cat $ST/dnsmasq.pid 2>/dev/null)
  alive "$P" && kill -HUP "$P"
}

start_dnsmasq() {
  # dnsmasq daemonizes; never capture its output with $(...) or the shell waits on the pipe forever.
  $DNSMASQ -C $ST/dnsmasq.conf </dev/null >$ST/dnsmasq.out 2>&1
  sleep 1
  if ! alive "$(cat $ST/dnsmasq.pid 2>/dev/null)"; then
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
  src_rule "$mac" "$grp"
  gen_hosts
  log "connect $mac group=$grp"
}

on_disconnect() {
  mac=""; next=0
  for w in $1; do
    [ $next = 1 ] && { mac=$w; next=0; }
    [ "$w" = AP-STA-DISCONNECTED ] && next=1
  done
  is_mac "$mac" || return
  mac=$(echo "$mac" | tr 'A-F' 'a-f')
  grep -v "^$mac " $ST/connected > $ST/conn.tmp 2>/dev/null; mv $ST/conn.tmp $ST/connected
  log "disconnect $mac"
}

watchdog() {
  IF=$(cfg IFACE)
  while true; do
    sleep 10
    [ "$(cat $ST/state 2>/dev/null)" = running ] || continue
    if ! alive "$(hostapd_pid)"; then
      log "hostapd died"
      echo failed > $ST/state
      continue
    fi
    if ! alive "$(cat $ST/dnsmasq.pid 2>/dev/null)"; then
      log "dnsmasq died - restarting"
      start_dnsmasq
    fi
    apply_upstream
    [ "$(cat /proc/sys/net/ipv4/ip_forward)" = 1 ] || { echo 1 > /proc/sys/net/ipv4/ip_forward; log "ip_forward re-enabled"; }
    fw_hooks_ok || { log "firewall hooks missing - rebuilding"; fw_setup "$IF"; while read -r m g; do src_rule "$m" "$g"; done < $ST/map; }
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

# ---------------------------------------------------------------- start / stop

start() {
  CFG=$1
  [ -f "$CFG/engine.conf" ] && [ -f "$CFG/hostapd.conf" ] && [ -f "$CFG/groups.conf" ] || { echo "ERROR: bad config dir"; exit 1; }
  stop >/dev/null 2>&1

  mkdir -p $ST $HD; chmod 700 $ST
  rm -rf $ST/cfg; mkdir $ST/cfg; cp "$CFG"/* $ST/cfg/
  [ "$(readlink -f "$0")" != "$ST/mh_engine.sh" ] && cp "$0" $ST/mh_engine.sh
  : > $ST/engine.log; : > $ST/map; : > $ST/connected; : > $ST/hosts.dhcp; : > $ST/upstream
  touch $ST/cfg/static.conf
  echo starting > $ST/state

  IF=$(cfg IFACE); MAC=$(cfg MAC); WM=$(cfg WIFI_MODE)
  [ -d /sys/class/net/$IF ] || { fail "interface $IF not found"; exit 1; }
  log "start iface=$IF wifi_mode=$WM"

  cp $ST/cfg/hostapd.conf $HD/hostapd.conf
  cp $ST/cfg/psk.txt $HD/psk.txt
  if [ -f $ST/cfg/deny.txt ]; then cp $ST/cfg/deny.txt $HD/deny.txt; else rm -f $HD/deny.txt; fi
  chown -R wifi:wifi $HD; chmod 770 $HD; chmod 660 $HD/*; restorecon -R $HD

  cmd wifi stop-softap >/dev/null 2>&1
  if [ "$WM" = off ] && cmd wifi status 2>/dev/null | grep -q "Wifi is enabled"; then
    touch $ST/wifi_was_on
    cmd wifi set-wifi-enabled disabled
    sleep 3
  fi

  cat /proc/sys/net/ipv4/ip_forward > $ST/ip_forward.orig
  cat /proc/sys/net/ipv4/conf/all/send_redirects > $ST/send_redirects.orig

  # Event loop must be running before the AP accepts clients.
  setsid sh $ST/mh_engine.sh daemon </dev/null >/dev/null 2>&1 &

  ip link set $IF down
  [ -n "$MAC" ] && ip link set $IF address $MAC
  rm -f $CTRL_DIR/$IF
  setsid $HOSTAPD $HD/hostapd.conf </dev/null >/dev/null 2>&1 &

  n=0; up=0
  while [ $n -lt 15 ]; do
    sleep 1; n=$((n+1))
    alive "$(hostapd_pid)" || break
    [ "$(cat /sys/class/net/$IF/operstate 2>/dev/null)" = up ] && { up=1; break; }
  done
  if [ $up != 1 ]; then
    fail "access point did not start (driver refused the settings - see logcat tag hostapd)"
    stop >/dev/null 2>&1; echo failed > $ST/state
    exit 1
  fi
  log "AP up after ${n}s"

  ip addr flush dev $IF
  while read -r id gw pfx net mask inet iso ds de lt dns; do
    case "$id" in ''|\#*) continue ;; esac
    ip addr add $gw/$pfx dev $IF
    ip route replace $net dev $IF table local_network
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

  fw_setup $IF
  apply_upstream || log "no upstream yet - will retry"

  gen_dnsmasq $IF
  if ! start_dnsmasq; then
    fail "DHCP server failed to start"
    stop >/dev/null 2>&1; echo failed > $ST/state
    exit 1
  fi

  echo running > $ST/state
  log "running"
  echo "OK"
}

stop() {
  IF=$(cfg IFACE)
  for f in watchdog daemon; do
    P=$(cat $ST/$f.pid 2>/dev/null); alive "$P" && kill "$P" 2>/dev/null
    rm -f $ST/$f.pid
  done
  pkill -f "mh_engine[.]sh daemon" 2>/dev/null
  pkill -f "logcat -T 1 -b main -s hostap[d]" 2>/dev/null
  pkill -f "hostapd $HD/hostapd[.]conf" 2>/dev/null
  P=$(cat $ST/dnsmasq.pid 2>/dev/null); alive "$P" && kill "$P" 2>/dev/null
  pkill -f "dnsmasq -C $ST/dnsmasq[.]conf" 2>/dev/null
  rm -f $ST/dnsmasq.pid

  fw_teardown
  while ip rule del pref $PREF 2>/dev/null; do :; done

  if [ -n "$IF" ]; then
    while read -r id gw pfx net rest; do
      case "$id" in ''|\#*) continue ;; esac
      ip route del $net dev $IF table local_network 2>/dev/null
    done < $ST/cfg/groups.conf
    ip addr flush dev $IF 2>/dev/null
    ip link set $IF down 2>/dev/null
    echo 0 > /proc/sys/net/ipv4/conf/$IF/proxy_arp 2>/dev/null
    echo 0 > /proc/sys/net/ipv4/conf/$IF/proxy_arp_pvlan 2>/dev/null
    rm -f $CTRL_DIR/$IF
  fi
  [ -f $ST/ip_forward.orig ] && cat $ST/ip_forward.orig > /proc/sys/net/ipv4/ip_forward && rm -f $ST/ip_forward.orig
  [ -f $ST/send_redirects.orig ] && cat $ST/send_redirects.orig > /proc/sys/net/ipv4/conf/all/send_redirects && rm -f $ST/send_redirects.orig
  if [ -f $ST/wifi_was_on ]; then
    rm -f $ST/wifi_was_on
    cmd wifi set-wifi-enabled enabled
  fi
  : > $ST/connected 2>/dev/null; : > $ST/upstream 2>/dev/null
  [ "$(cat $ST/state 2>/dev/null)" = failed ] || echo stopped > $ST/state
  log "stopped"
  echo "OK"
}

status() {
  echo "STATE=$(cat $ST/state 2>/dev/null || echo stopped)"
  echo "IFACE=$(cfg IFACE)"
  echo "UPSTREAM=$(cat $ST/upstream 2>/dev/null)"
  alive "$(hostapd_pid)" && echo "HOSTAPD=1" || echo "HOSTAPD=0"
  alive "$(cat $ST/dnsmasq.pid 2>/dev/null)" && echo "DHCP=1" || echo "DHCP=0"
  alive "$(cat $ST/daemon.pid 2>/dev/null)" && echo "DAEMON=1" || echo "DAEMON=0"
  # Bytes sent by each client (counter of its anti-spoof rule).
  # iptables prints the match as "MACaa:bb:..." (some builds: "MAC aa:bb:...").
  $IPT -L mh_SRC -v -x -n 2>/dev/null | awk '$3=="RETURN" {for(i=1;i<=NF;i++) if($i ~ /^MAC/) {m=substr($i,4); if(m=="") m=$(i+1); print tolower(m), $2}}' > $ST/bytes.tmp
  while read -r mac grp since; do
    [ -z "$mac" ] && continue
    ip=$(awk -v m="$mac" 'tolower($2)==m {print $3; exit}' $ST/dnsmasq.leases 2>/dev/null)
    host=$(awk -v m="$mac" 'tolower($2)==m {print $4; exit}' $ST/dnsmasq.leases 2>/dev/null)
    up=$(awk -v m="$mac" '$1==m {print $2; exit}' $ST/bytes.tmp)
    echo "CLIENT $mac $grp ${ip:--} ${host:--} $since ${up:-0}"
  done < $ST/connected
  rm -f $ST/bytes.tmp
  echo "LOG_BEGIN"
  tail -n 15 $ST/engine.log 2>/dev/null
  echo "LOG_END"
}

case "$1" in
  start)  start "$2" ;;
  stop)   stop ;;
  status) status ;;
  daemon) daemon ;;
  *) echo "usage: $0 start <cfgdir>|stop|status"; exit 2 ;;
esac
