package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.List;

public final class KnownServerProfiles {
    private KnownServerProfiles() {
    }

    public static List<ServerProfile> all() {
        return List.of(
                profile("Bell reference", "one.object-camera.com,174.129.34.188,four.object-camera.com"),
                profile("HIP2P · SY WWWW/TTTT/UUUU",
                        "EEGDFHBAKJINGNJBEHHNFDEMHINCHENLHLFKBEDLAAJCKCKJDIAFDDPDGOKAIMLLAJNDKGDFPJNGBECDIL",
                        "WWWW", "TTTT", "UUUU"),
                profile("HIP2P · SY AAAA–EEEE",
                        "EFGNFJBOKAIEGHJOEKHAFPEDGLNMHMNHHHFKBADPAGJDLLKPDMANCCPKGFLBIBLCAMMLKHDOOKNKBPCIJEMA",
                        "AAAA", "BBBB", "CCCC", "DDDD", "EEEE"),
                profile("HIP2P · AAES",
                        "EBGIECBCKIJDHBJBEEGJECECHHMJGONKGFEEAJCGBMILKFLBCCBPDCOIHDKLIEKKBNNAKCCGPGNJBNDBIEMFJEBMNDOJAJCB",
                        "AAES"),
                profile("HIP2P · Dericam DEAA",
                        "EFGHFDBFLDILGMJGFMHJFBEJGBJHDHNPGCFEENCJFDJMOBLKHFFPCGLNHKLGNIOIBONILEHKPNJBAMGNIIJNNFBAIBOOBG",
                        "DEAA"),
                profile("HIP2P · FDTAA",
                        "EFGBFFBNKCIGGAJIFCHEFJEOGGNFHFMDHIFHAEDDAJIBKOKEDGBODBPBHFKEIMLLBDNEKCCGPFNPBLDF",
                        "FDTAA"),
                profile("HIP2P · SECRUI",
                        "EEGDFHBAKJINGGJMFGHDFMEBGJNLHOMIHOFPAMDPACJGKGKODJBBDKPOGFKLIGLDBLNOKIDKPGNJBICMIE",
                        "SECRUI"),
                profile("HIP2P · XQ FFFF/HHHH/GGGG",
                        "LPLXSXSULKPELOEHHUARENEESTPHEPIHPDHYHZAOLRICSQLNEKEJPAHXIBEMERELEILKIEHUHWEEEHEOEG-$$",
                        "FFFF", "HHHH", "GGGG"),
                profile("HIP2P · XQ IIII/JJJJ/KKKK",
                        "EKPNAUENAOIFSWSQLPAXPALNAYPGLXHXSUEGLKSZICHUPETAEEEHELHZIHLQPJAOPKEPSQIALUPALOHYLTEREKIDLKIEEOHUEIEJEEEMEN-$$",
                        "IIII", "JJJJ", "KKKK"),
                profile("HIP2P · XQ XXXX/YYYY/ZZZZ",
                        "EJLXEKLTLKIFAUHULRICEESWLNIHPGIDAOEPHXSQEHEMIBPALQIAEREKENLKIEEOHUHWEEELEG-$$",
                        "XXXX", "YYYY", "ZZZZ"),
                new ServerProfile("PPCS · LAN only", List.of(), TransportProtocol.PPCS),
                new ServerProfile("XQ · LAN only", List.of(), TransportProtocol.XQ),
                new ServerProfile("Yoosee · GWell LAN discovery", List.of(), TransportProtocol.GWELL),
                yoosee("Yoosee · GWell remote presence (account required)", TransportProtocol.GWELL),
                yoosee("Yoosee · IoTVideo remote presence (account required)", TransportProtocol.IOTVIDEO));
    }

    private static ServerProfile profile(String name, String configuration, String... prefixes) {
        var parsed = ServerConfigurationParser.parse(configuration);
        return new ServerProfile(name, parsed.servers(), parsed.protocol().orElse(TransportProtocol.PPCS),
                List.of(prefixes));
    }

    private static ServerProfile yoosee(String name, TransportProtocol protocol) {
        var reference = YooseeServerReferences.production().stream().filter(item -> item.protocol() == protocol).findFirst().orElseThrow();
        return new ServerProfile(name, reference.hosts().stream()
                .map(host -> java.net.InetSocketAddress.createUnresolved(host, 51701)).toList(), protocol);
    }
}
