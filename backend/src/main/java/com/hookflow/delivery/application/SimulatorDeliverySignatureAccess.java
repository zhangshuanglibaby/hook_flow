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
        if (signature == null || signature.length != 32) {
            return false;
        }
        var event = events.find(eventId);
        if (event.isEmpty()) {
            return false;
        }
        var identity = event.orElseThrow();
        var endpoint = endpoints.findById(identity.endpointId());
        if (endpoint.isEmpty() || endpoint.orElseThrow().targetType() != EndpointTargetType.SIMULATOR
                || endpoint.orElseThrow().url() != null) {
            return false;
        }
        try {
            return snapshots.matches(identity.eventPk(), identity.latestRoundNo(), ciphertext -> {
                String secret = secrets.decryptForSimulatorVerification(ciphertext);
                byte[] expected = HexFormat.of().parseHex(signer.sign(eventId, timestamp, rawBody, secret));
                return MessageDigest.isEqual(expected, signature);
            });
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return false;
        }
    }
}
