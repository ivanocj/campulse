package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.HostResolver;
import com.ivanocj.cameras.availability.application.port.YooseeCredentialSource;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.protocol.IotVideoModelPresence;
import com.ivanocj.cameras.availability.infrastructure.protocol.IotVideoRemoteMessages;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** IoTVideo type-1 account -> token certification -> negotiated session ID -> mappings -> correlated model. */
public final class IotVideoRemoteProbe extends AbstractYooseeRemoteProbe {
    private final SecureRandom random = new SecureRandom();
    public IotVideoRemoteProbe(YooseeCredentialSource credentials, HostResolver resolver, Clock clock) {
        super(TransportProtocol.IOTVIDEO, credentials, resolver, clock);
    }
    @Override protected long identity(YooseeAccount account) throws IOException {
        require(account instanceof YooseeAccount.IotVideo);
        return ((YooseeAccount.IotVideo) account).wireId();
    }
    @Override protected byte[] listRequest(long identity) { return IotVideoRemoteMessages.list(identity); }

    @Override protected RemotePresence query(ProbeRequest request, YooseeAccount supplied, GuteTcpSession session,
                                             GuteFrameCodec codec) throws IOException {
        var account = (YooseeAccount.IotVideo) supplied;
        int sequence = (random.nextInt() & 0x3fffffff) + 1;
        var key = new byte[32];
        var nonce = new byte[12];
        random.nextBytes(key);
        random.nextBytes(nonce);
        long sessionId;
        try {
            var encoded = IotVideoRemoteMessages.certify(account, key, nonce, sequence, System.nanoTime() / 1_000_000, codec);
            byte[] certified;
            try { certified = session.exchangeEncoded(encoded, 0x0c, sequence, true); }
            finally { Arrays.fill(encoded, (byte) 0); }
            require(certified.length >= 36);
            if (u16(certified, 26) != 0) { throw new GuteTcpSession.AuthenticationFailed(); }
            sessionId = i64(certified, 28);
            require(sessionId != 0);
            codec.negotiate(key);
            session.sessionIdentity(sessionId);
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(nonce, (byte) 0); }
        var registered = session.exchange(IotVideoRemoteMessages.initInfo(sessionId, ++sequence), true);
        require(codec.mode(registered) == 2);
        if (registered.length >= 28 && u16(registered, 26) != 0) { throw new GuteTcpSession.AuthenticationFailed(); }
        var mappings = IotVideoRemoteMessages.identities(registered);
        long target = IotVideoRemoteMessages.resolveIdentity(request.identifier().value(), mappings);
        var response = session.exchange(IotVideoRemoteMessages.model(sessionId, target, ++sequence), true);
        require(codec.mode(response) == 2);
        return IotVideoModelPresence.parse(IotVideoRemoteMessages.modelJson(response, target), clock.instant());
    }
}
