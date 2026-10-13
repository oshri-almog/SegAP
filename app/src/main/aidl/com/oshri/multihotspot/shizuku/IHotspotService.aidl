package com.oshri.multihotspot.shizuku;

// The Shizuku engine's service (runs as the ADB shell user, see HotspotUserService).
interface IHotspotService {
    // Shizuku's UserService convention: called before the service is removed.
    void destroy() = 16777114;

    // Applies the settings and (re)starts the hotspot. Returns "" or an error.
    String start(String ssid, String passphrase, boolean band5, int channel, boolean hidden, int maxClients,
                 in List<String> blocked, boolean isolate) = 1;

    String stop() = 2;

    // Status in the root engine's text format (EngineStatus.parse); clients are reported in group [groupId].
    String status(String groupId) = 3;
}
