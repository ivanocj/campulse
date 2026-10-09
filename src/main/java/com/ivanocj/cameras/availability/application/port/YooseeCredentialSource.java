package com.ivanocj.cameras.availability.application.port;

import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.util.Optional;

@FunctionalInterface
public interface YooseeCredentialSource {
    /** A fresh caller-owned snapshot, safe for concurrent checks; empty means not configured. */
    Optional<YooseeAccount> load(TransportProtocol protocol) throws IOException;
}
