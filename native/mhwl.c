/*
 * mhwl - per-station radio stats from the Broadcom (bcmdhd) driver for MultiHotspot.
 *
 * hostapd's STA command reports signal=0 and byte counters of 0 on this driver in AP mode, but the
 * firmware keeps a "sta_info" record per associated station. This asks for it with the same private
 * ioctl Broadcom's `wl sta_info <mac>` uses (SIOCDEVPRIVATE + wl_ioctl_t, WLC_GET_VAR). Needs root.
 *
 * usage: mhwl <iface> <mac> [<mac>...]     one line per station:
 *          STA <mac> <rssi_dbm|-> <tx_bytes> <rx_bytes> <tx_rate_kbps> <rx_rate_kbps> <idle_s>
 *          ERR <mac> <reason>
 *        (tx = phone -> device, i.e. the device's download; rx = device -> phone, its upload)
 *        mhwl -x <iface> <mac>                 hex dump of the raw record (layout debugging)
 *        mhwl -g <iface> <iovar>               integer firmware variable, e.g. qtxpower (quarter dBm), chanspec
 *        mhwl -s <iface> <iovar> <value>       set it (the engine's transmit power limit uses qtxpower)
 *        mhwl -t <iface>                       interface type (nl80211): "station", "ap" or the number
 *        mhwl -t <iface> station|ap            set it - hostapd on wlan0 leaves it "ap" when it exits, and the
 *                                              phone's Wi-Fi then can't connect until it is set back
 */
#include <errno.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/sockios.h>
#include <linux/netlink.h>
#include <linux/genetlink.h>

#define WLC_GET_VAR 262
#define WLC_SET_VAR 263
#define BUF_LEN 1536

/* Layout the bcmdhd ioctl entry copies from user space: wl_ioctl_t followed by a 4-byte "driver"
 * field (0 = firmware/wl ioctl, DHD_IOCTL_MAGIC = dhd ioctl). Natural alignment, 64-bit pointer. */
struct wl_ioctl_req {
    uint32_t cmd;
    void *buf;
    uint32_t len;
    uint8_t set;
    uint32_t used;
    uint32_t needed;
    uint32_t driver;
};

/* Offsets into sta_info_t (versions 4 and later share this prefix). */
#define OFF_VER         0   /* uint16 */
#define OFF_LEN         2   /* uint16 */
#define OFF_IDLE        12  /* uint32, seconds since last data frame from the station */
#define OFF_EA          16  /* struct ether_addr */
#define OFF_TX_RATE     68  /* uint32 kbps, last tx frame */
#define OFF_RX_RATE     72  /* uint32 kbps, last rx frame */
#define OFF_TX_TOT_B    96  /* uint64 */
#define OFF_RX_TOT_B    104 /* uint64 */
#define OFF_RSSI        144 /* int8[4], average rssi per antenna */
#define OFF_LAST_RSSI   168 /* int8[4], rssi of last received data frame per antenna */
#define MIN_LEN         172

static int parse_mac(const char *s, uint8_t m[6]) {
    unsigned v[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return -1;
    for (int i = 0; i < 6; i++) {
        if (v[i] > 255) return -1;
        m[i] = (uint8_t)v[i];
    }
    return 0;
}

static int sta_info(int fd, const char *ifname, const uint8_t mac[6], uint8_t *buf) {
    static const char name[] = "sta_info";
    memset(buf, 0, BUF_LEN);
    memcpy(buf, name, sizeof(name));
    memcpy(buf + sizeof(name), mac, 6);

    struct wl_ioctl_req ioc;
    memset(&ioc, 0, sizeof(ioc));
    ioc.cmd = WLC_GET_VAR;
    ioc.buf = buf;
    ioc.len = BUF_LEN;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    ifr.ifr_data = (char *)&ioc;
    return ioctl(fd, SIOCDEVPRIVATE, &ifr);
}

static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t u32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static uint64_t u64(const uint8_t *p) { return (uint64_t)u32(p) | (uint64_t)u32(p + 4) << 32; }

/* Average of the antennas that report a plausible value (0 = antenna unused). */
static int avg_rssi(const uint8_t *p) {
    int sum = 0, n = 0;
    for (int i = 0; i < 4; i++) {
        int v = (int8_t)p[i];
        if (v < 0 && v > -110) { sum += v; n++; }
    }
    return n ? sum / n : 0;
}

/* Integer firmware variable ("iovar"), as `wl <name>` / `wl <name> <value>` would read or set it. */
static int iovar_int(int fd, const char *ifname, const char *name, int set, int32_t *val) {
    static uint8_t buf[BUF_LEN];
    size_t n = strlen(name) + 1;
    if (n + 4 > BUF_LEN) return -1;
    memset(buf, 0, BUF_LEN);
    memcpy(buf, name, n);
    if (set) memcpy(buf + n, val, 4);

    struct wl_ioctl_req ioc;
    memset(&ioc, 0, sizeof(ioc));
    ioc.cmd = set ? WLC_SET_VAR : WLC_GET_VAR;
    ioc.buf = buf;
    ioc.len = set ? (uint32_t)(n + 4) : BUF_LEN;
    ioc.set = (uint8_t)set;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    ifr.ifr_data = (char *)&ioc;
    if (ioctl(fd, SIOCDEVPRIVATE, &ifr) != 0) return -1;
    if (!set) memcpy(val, buf, 4);
    return 0;
}

/* ---- nl80211 over generic netlink (the phone has no iw) ---- */

#define NL80211_CMD_GET_INTERFACE 5
#define NL80211_CMD_SET_INTERFACE 6
#define NL80211_ATTR_IFINDEX 3
#define NL80211_ATTR_IFTYPE 5
#define NL80211_IFTYPE_STATION 2
#define NL80211_IFTYPE_AP 3

static void put_attr(char *msg, int *len, uint16_t type, const void *data, uint16_t dlen) {
    struct nlattr *a = (struct nlattr *)(msg + *len);
    a->nla_type = type;
    a->nla_len = NLA_HDRLEN + dlen;
    memcpy((char *)a + NLA_HDRLEN, data, dlen);
    *len += NLA_ALIGN(a->nla_len);
}

/* Sends one request; returns the reply length (or -errno). An ack/error reply's code is in *err. */
static int genl_call(int fd, char *msg, int len, char *rep, int rlen, int *err) {
    struct sockaddr_nl to;
    memset(&to, 0, sizeof(to));
    to.nl_family = AF_NETLINK;
    if (sendto(fd, msg, len, 0, (struct sockaddr *)&to, sizeof(to)) < 0) return -errno;
    int n = recv(fd, rep, rlen, 0);
    if (n < 0) return -errno;
    struct nlmsghdr *h = (struct nlmsghdr *)rep;
    *err = (h->nlmsg_type == NLMSG_ERROR) ? ((struct nlmsgerr *)NLMSG_DATA(h))->error : 0;
    return n;
}

/* Value of attribute [type] in a generic netlink reply, or NULL. */
static const char *find_attr(const char *rep, uint16_t type, int *alen) {
    const struct nlmsghdr *h = (const struct nlmsghdr *)rep;
    const char *p = (const char *)NLMSG_DATA(h) + GENL_HDRLEN;
    int rem = (int)h->nlmsg_len - NLMSG_HDRLEN - GENL_HDRLEN;
    while (rem >= NLA_HDRLEN) {
        const struct nlattr *a = (const struct nlattr *)p;
        if (a->nla_len < NLA_HDRLEN || a->nla_len > rem) break;
        if ((a->nla_type & NLA_TYPE_MASK) == type) { *alen = a->nla_len - NLA_HDRLEN; return p + NLA_HDRLEN; }
        p += NLA_ALIGN(a->nla_len);
        rem -= NLA_ALIGN(a->nla_len);
    }
    return NULL;
}

static int nl80211_family(int fd) {
    char msg[128], rep[4096];
    memset(msg, 0, sizeof(msg));
    int len = NLMSG_HDRLEN + GENL_HDRLEN, err = 0;
    struct nlmsghdr *h = (struct nlmsghdr *)msg;
    struct genlmsghdr *g = (struct genlmsghdr *)NLMSG_DATA(h);
    h->nlmsg_type = GENL_ID_CTRL;
    h->nlmsg_flags = NLM_F_REQUEST;
    h->nlmsg_seq = 1;
    g->cmd = CTRL_CMD_GETFAMILY;
    g->version = 1;
    put_attr(msg, &len, CTRL_ATTR_FAMILY_NAME, "nl80211", 8);
    h->nlmsg_len = len;
    int n = genl_call(fd, msg, len, rep, sizeof(rep), &err);
    if (n < 0) return n;
    if (err) return err;
    int alen;
    const char *v = find_attr(rep, CTRL_ATTR_FAMILY_ID, &alen);
    return (v && alen >= 2) ? *(const uint16_t *)v : -ENOENT;
}

/* Gets (type < 0) or sets the nl80211 interface type; prints the type when getting. */
static int iftype(const char *ifname, int type) {
    uint32_t idx = if_nametoindex(ifname);
    if (!idx) { printf("ERR iftype no-such-interface\n"); return 1; }
    int fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_nl me;
    memset(&me, 0, sizeof(me));
    me.nl_family = AF_NETLINK;
    if (bind(fd, (struct sockaddr *)&me, sizeof(me)) < 0) { perror("bind"); close(fd); return 1; }
    int fam = nl80211_family(fd);
    if (fam < 0) { printf("ERR iftype nl80211-%d\n", -fam); close(fd); return 1; }

    char msg[128], rep[8192];
    memset(msg, 0, sizeof(msg));
    int len = NLMSG_HDRLEN + GENL_HDRLEN, err = 0;
    struct nlmsghdr *h = (struct nlmsghdr *)msg;
    struct genlmsghdr *g = (struct genlmsghdr *)NLMSG_DATA(h);
    h->nlmsg_type = (uint16_t)fam;
    h->nlmsg_flags = NLM_F_REQUEST | (type >= 0 ? NLM_F_ACK : 0);
    h->nlmsg_seq = 2;
    g->cmd = type >= 0 ? NL80211_CMD_SET_INTERFACE : NL80211_CMD_GET_INTERFACE;
    put_attr(msg, &len, NL80211_ATTR_IFINDEX, &idx, 4);
    if (type >= 0) { uint32_t t = (uint32_t)type; put_attr(msg, &len, NL80211_ATTR_IFTYPE, &t, 4); }
    h->nlmsg_len = len;
    int n = genl_call(fd, msg, len, rep, sizeof(rep), &err);
    close(fd);
    if (n < 0 || err) { printf("ERR iftype %d\n", n < 0 ? -n : -err); return 1; }
    if (type >= 0) return 0;
    int alen;
    const char *v = find_attr(rep, NL80211_ATTR_IFTYPE, &alen);
    if (!v || alen < 4) { printf("ERR iftype no-type\n"); return 1; }
    uint32_t t = *(const uint32_t *)v;
    if (t == NL80211_IFTYPE_STATION) printf("station\n");
    else if (t == NL80211_IFTYPE_AP) printf("ap\n");
    else printf("%u\n", t);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "-t") == 0) {
        if (argc == 3) return iftype(argv[2], -1);
        if (strcmp(argv[3], "station") == 0) return iftype(argv[2], NL80211_IFTYPE_STATION);
        if (strcmp(argv[3], "ap") == 0) return iftype(argv[2], NL80211_IFTYPE_AP);
        fprintf(stderr, "usage: mhwl -t <iface> [station|ap]\n");
        return 2;
    }
    /* mhwl -g <iface> <iovar>          prints the integer value (e.g. qtxpower, chanspec)
     * mhwl -s <iface> <iovar> <value>  sets it */
    if (argc >= 4 && (strcmp(argv[1], "-g") == 0 || strcmp(argv[1], "-s") == 0)) {
        int set = argv[1][1] == 's';
        if (set && argc < 5) { fprintf(stderr, "usage: mhwl -s <iface> <iovar> <value>\n"); return 2; }
        int fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (fd < 0) { perror("socket"); return 1; }
        int32_t v = set ? (int32_t)strtol(argv[4], NULL, 0) : 0;
        int r = iovar_int(fd, argv[2], argv[3], set, &v);
        close(fd);
        if (r != 0) { printf("ERR %s ioctl-%d\n", argv[3], errno); return 1; }
        if (!set) printf("%s %d 0x%x\n", argv[3], v, (unsigned)v);
        return 0;
    }
    int hex = 0, a = 1;
    if (argc > 1 && strcmp(argv[1], "-x") == 0) { hex = 1; a = 2; }
    if (argc - a < 2) {
        fprintf(stderr, "usage: mhwl [-x] <iface> <mac> [<mac>...]\n");
        return 2;
    }
    const char *ifname = argv[a++];
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    static uint8_t buf[BUF_LEN];
    for (; a < argc; a++) {
        uint8_t mac[6];
        if (parse_mac(argv[a], mac) != 0) { printf("ERR %s bad-mac\n", argv[a]); continue; }
        if (sta_info(fd, ifname, mac, buf) != 0) { printf("ERR %s ioctl-%d\n", argv[a], errno); continue; }

        uint16_t ver = u16(buf + OFF_VER), len = u16(buf + OFF_LEN);
        if (hex) {
            printf("ver=%u len=%u\n", ver, len);
            for (int i = 0; i < len && i < BUF_LEN; i++) printf("%02x%s", buf[i], (i % 16 == 15) ? "\n" : " ");
            printf("\n");
            continue;
        }
        if (ver < 4 || len < MIN_LEN || memcmp(buf + OFF_EA, mac, 6) != 0) {
            printf("ERR %s layout-ver%u-len%u\n", argv[a], ver, len);
            continue;
        }
        int rssi = avg_rssi(buf + OFF_RSSI);
        if (rssi == 0) rssi = avg_rssi(buf + OFF_LAST_RSSI);
        char rs[8];
        if (rssi) snprintf(rs, sizeof(rs), "%d", rssi); else strcpy(rs, "-");
        printf("STA %s %s %llu %llu %u %u %u\n", argv[a], rs,
               (unsigned long long)u64(buf + OFF_TX_TOT_B), (unsigned long long)u64(buf + OFF_RX_TOT_B),
               u32(buf + OFF_TX_RATE), u32(buf + OFF_RX_RATE), u32(buf + OFF_IDLE));
    }
    close(fd);
    return 0;
}
