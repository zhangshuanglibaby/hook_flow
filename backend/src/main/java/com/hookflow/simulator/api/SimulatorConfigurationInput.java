package com.hookflow.simulator.api;

import com.hookflow.simulator.domain.SimulatorConfiguration;
import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class SimulatorConfigurationInput {
    private SimulatorConfigurationInput() { }

    public static SimulatorConfiguration parse(Map<String, Object> body) {
        if (body == null || body.keySet().stream().anyMatch(key ->
                !key.equals("mode") && !key.equals("failFirstN") && !key.equals("delayMs"))
                || !(body.get("mode") instanceof String)) throw invalid();
        try {
            return new SimulatorConfiguration(Mode.valueOf((String) body.get("mode")),
                    body.containsKey("failFirstN") ? integer(body.get("failFirstN")) : 0,
                    body.containsKey("delayMs") ? integer(body.get("delayMs")) : 0);
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer number)) throw invalid();
        return number;
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
