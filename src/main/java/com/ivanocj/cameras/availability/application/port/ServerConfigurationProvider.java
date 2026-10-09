package com.ivanocj.cameras.availability.application.port;

import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.List;

public interface ServerConfigurationProvider {
    List<ServerProfile> profiles();

    ServerConfiguration parse(String input);

    default ServerConfiguration parse(String input, TransportProtocol protocol) { return parse(input); }
}
