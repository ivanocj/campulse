package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.HostResolver;
import com.ivanocj.cameras.availability.application.port.YooseeCredentialSource;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.protocol.GwellRemoteMessages;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** GWell account -> TCP init -> certification -> RSA/RC5 key update -> correlated 0x2D status. */
public final class GwellRemoteProbe extends AbstractYooseeRemoteProbe {
    private final SecureRandom random = new SecureRandom();
    public GwellRemoteProbe(YooseeCredentialSource credentials, HostResolver resolver, Clock clock) {
        super(TransportProtocol.GWELL, credentials, resolver, clock);
    }
    @Override protected long identity(YooseeAccount account) throws IOException {
        require(account instanceof YooseeAccount.Gwell);
        return ((YooseeAccount.Gwell) account).userId();
    }
    @Override protected byte[] listRequest(long identity) { return GwellRemoteMessages.list(identity); }

    @Override protected RemotePresence query(ProbeRequest request, YooseeAccount supplied, GuteTcpSession session,
                                             GuteFrameCodec codec) throws IOException {
        var account = (YooseeAccount.Gwell) supplied;
        int sequence = random.nextInt() & 0x3fffffff;
        session.exchange(GwellRemoteMessages.initInfo(account, ++sequence, 1), false);
        var certified = session.exchange(GwellRemoteMessages.certify(account, ++sequence), true);
        require(certified.length >= 32);
        if (u16(certified, 22) != 0) { throw new GuteTcpSession.AuthenticationFailed(); }
        var key = GwellRemoteMessages.keyMaterial(account, clock.instant());
        try {
            var updated = session.exchange(GwellRemoteMessages.updateKey(account, ++sequence, certified, key, random), true);
            require(updated.length >= 24);
            if (u16(updated, 22) != 0) { throw new GuteTcpSession.AuthenticationFailed(); }
            codec.negotiate(key);
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(certified, (byte) 0); }
        var ready = session.exchange(GwellRemoteMessages.initInfo(account, ++sequence, 2), false);
        if (u16(ready, 22) != 0) { throw new GuteTcpSession.AuthenticationFailed(); }
        int target = ((GwellDeviceId) request.identifier()).wireValue();
        var response = session.exchange(GwellRemoteMessages.status(account.userId(), target, ++sequence, 2), true);
        require(codec.mode(response) == 2);
        var state = GwellRemoteMessages.presence(response, target);
        return new RemotePresence(state, TransportProtocol.GWELL, RemotePresence.Source.GWELL_STATUS_QUERY,
                Optional.empty(), clock.instant());
    }
}
