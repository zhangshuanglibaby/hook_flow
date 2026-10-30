package com.hookflow.endpoint.application;

import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!skeleton")
@Service
public class EndpointSnapshotSecretAccess {
    private final EndpointSigningSecretCipher secrets;

    public EndpointSnapshotSecretAccess(EndpointSigningSecretCipher secrets) {
        this.secrets = secrets;
    }

    public String decryptForSimulatorVerification(byte[] snapshot) {
        return secrets.decryptForDelivery(snapshot);
    }
}
