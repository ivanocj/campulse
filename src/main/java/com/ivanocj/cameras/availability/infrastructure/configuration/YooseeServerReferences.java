package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.List;

/** Recovered production list-server names, not verified availability presets or PPCS/XQ endpoints. */
public final class YooseeServerReferences {
    private YooseeServerReferences() {
    }

    public static List<ListServerReference> production() {
        return List.of(new ListServerReference(TransportProtocol.GWELL,
                        List.of("p2p1.cloudlinks.cn", "p2p3.cloud-links.net", "p2p2.cloudlinks.cn", "p2p4.cloud-links.net",
                                "p2p5.cloudlinks.cn", "p2p6.cloudlinks.cn", "p2p7.cloudlinks.cn", "p2p8.cloudlinks.cn",
                                "p2p9.cloudlinks.cn", "p2p10.cloudlinks.cn"),
                        "GSdkInitor P2P_HOSTLIST fallback; separate GWell list/session protocol, not PPCS."),
                new ListServerReference(TransportProtocol.IOTVIDEO, List.of("list.iotvideo.cloudlinks.cn"),
                        "ReleaseHostUrl production selection; authenticated IoTVideo SDK registration."));
    }

    public record ListServerReference(TransportProtocol protocol, List<String> hosts, String purpose) {
        public ListServerReference {
            hosts = List.copyOf(hosts);
        }
    }
}
