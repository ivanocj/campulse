package com.ivanocj.cameras.availability.testsupport;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import java.net.InetSocketAddress;

public final class DeviceFixtures {
    public static final DeviceId DID = new DeviceId("FFFF-158409-EBDAC");
    public static final InetSocketAddress SERVER = new InetSocketAddress("127.0.0.1", 32100);
    public static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 32108);
    public static final String XQ_FFFF_CONFIGURATION = "LPLXSXSULKPELOEHHUARENEESTPHEPIHPDHYHZAOLRICSQLNEKEJPAHXIBEMERELEILKIEHUHWEEEHEOEG-$$";

    private DeviceFixtures() {
    }
}
