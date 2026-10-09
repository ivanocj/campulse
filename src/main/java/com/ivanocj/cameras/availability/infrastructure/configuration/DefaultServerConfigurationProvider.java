package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.port.ServerConfigurationProvider;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.List;

public final class DefaultServerConfigurationProvider implements ServerConfigurationProvider {
    @Override
    public List<ServerProfile> profiles() {
        return KnownServerProfiles.all();
    }

    @Override
    public ServerConfiguration parse(String input) {
        return ServerConfigurationParser.parse(input);
    }

    @Override public ServerConfiguration parse(String input, TransportProtocol protocol) {
        return protocol.legacyUdp() ? parse(input) : ServerConfigurationParser.parseYoosee(input);
    }
}
