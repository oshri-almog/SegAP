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

#define WLC_GET_VAR 262
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

int main(int argc, char **argv) {
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
