package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorSignatureSnapshotRepository;
import com.hookflow.endpoint.application.EndpointQueryService;
import com.hookflow.endpoint.application.EndpointSnapshotSecretAccess;
import com.hookflow.endpoint.domain.EndpointTargetType;
import com.hookflow.event.application.SimulatorEventIdentity;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class SimulatorDeliverySignatureAccess {
    private final SimulatorEventIdentity events;
    private final EndpointQueryService endpoints;
    private final SimulatorSignatureSnapshotRepository snapshots;
    private final EndpointSnapshotSecretAccess secrets;
    private final DeliveryRequestSigner signer;

    public SimulatorDeliverySignatureAccess(SimulatorEventIdentity events, EndpointQueryService endpoints,
            SimulatorSignatureSnapshotRepository snapshots, EndpointSnapshotSecretAccess secrets,
            DeliveryRequestSigner signer) {
        this.events = events;
        this.endpoints = endpoints;
        this.snapshots = snapshots;
        this.secrets = secrets;
        this.signer = signer;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public boolean verify(String eventId, String timestamp, byte[] rawBody, byte[] signature) {
        return verifiedEventPk(eventId, timestamp, rawBody, signature) != null;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Long verifiedEventPk(String eventId, String timestamp, byte[] rawBody, byte[] signature) {
        if (signature == null || signature.length != 32) {
            return null;
        }
        var event = events.find(eventId);
        if (event.isEmpty()) {
            return null;
        }
        var identity = event.orElseThrow();
        var endpoint = endpoints.findById(identity.endpointId());
        if (endpoint.isEmpty() || endpoint.orElseThrow().targetType() != EndpointTargetType.SIMULATOR
                || endpoint.orElseThrow().url() != null) {
            return null;
        }
        try {
            boolean matched = snapshots.matches(identity.eventPk(), identity.latestRoundNo(), ciphertext -> {
                String secret = secrets.decryptForSimulatorVerification(ciphertext);
                byte[] expected = HexFormat.of().parseHex(signer.sign(eventId, timestamp, rawBody, secret));
                return MessageDigest.isEqual(expected, signature);
            });
            return matched ? identity.eventPk() : null;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return null;
        }
    }
}
