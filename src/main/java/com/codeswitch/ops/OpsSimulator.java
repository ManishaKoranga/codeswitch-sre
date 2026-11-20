package com.codeswitch.ops;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SIMULATED infrastructure for the hackathon demo: service metrics, deployment history and a status
 * page. Nothing here touches a real system. A production version would implement the same operations
 * against Prometheus/Datadog, the deployment platform and a status-page API.
 */
@Component
public class OpsSimulator {

    public record Metrics(String service, int windowMinutes, double p99LatencyMs, double errorRatePct,
                          String currentVersion) {
    }

    public record Deployment(String version, Instant deployedAt, String summary) {
    }

    private static final class ServiceState {
        double p99LatencyMs;
        double errorRatePct;
        String currentVersion;
        final List<Deployment> deployments = new ArrayList<>();
    }

    private final Clock clock;
    private final Map<String, ServiceState> services = new LinkedHashMap<>();
    private final List<String> statusPage = Collections.synchronizedList(new ArrayList<>());

    public OpsSimulator(Clock clock) {
        this.clock = clock;
        seed();
    }

    /** Demo incident: checkout started returning errors after version 2.14.0 was deployed. */
    private void seed() {
        Instant now = clock.instant();
        ServiceState checkout = new ServiceState();
        checkout.deployments.add(new Deployment("2.13.4", now.minusSeconds(86400), "Stable release"));
        checkout.deployments.add(new Deployment("2.14.0", now.minusSeconds(1800), "New payment retry logic"));
        checkout.currentVersion = "2.14.0";
        checkout.p99LatencyMs = 1840;
        checkout.errorRatePct = 4.2;
        services.put("checkout", checkout);

        ServiceState search = new ServiceState();
        search.deployments.add(new Deployment("5.1.0", now.minusSeconds(7200), "Index refresh"));
        search.currentVersion = "5.1.0";
        search.p99LatencyMs = 210;
        search.errorRatePct = 0.1;
        services.put("search", search);
    }

    public synchronized Optional<Metrics> metrics(String service, int windowMinutes) {
        ServiceState s = services.get(service);
        if (s == null) return Optional.empty();
        return Optional.of(new Metrics(service, windowMinutes, s.p99LatencyMs, s.errorRatePct, s.currentVersion));
    }

    public synchronized Optional<List<Deployment>> deployments(String service) {
        ServiceState s = services.get(service);
        if (s == null) return Optional.empty();
        return Optional.of(List.copyOf(s.deployments));
    }

    public synchronized Optional<String> previousVersion(String service) {
        ServiceState s = services.get(service);
        if (s == null || s.deployments.size() < 2) return Optional.empty();
        return Optional.of(s.deployments.get(s.deployments.size() - 2).version());
    }

    /** Simulated rollback: switches back to the previous version and the error rate recovers. */
    public synchronized String rollback(String service) {
        ServiceState s = requireService(service);
        String previous = previousVersion(service).orElseThrow(() -> new IllegalStateException("no previous version"));
        s.currentVersion = previous;
        s.errorRatePct = 0.3;
        s.p99LatencyMs = 320;
        return previous;
    }

    public void postStatus(String text) {
        statusPage.add(clock.instant() + " " + text);
    }

    public List<String> statusPage() {
        synchronized (statusPage) {
            return List.copyOf(statusPage);
        }
    }

    public boolean hasService(String service) {
        return services.containsKey(service);
    }

    private ServiceState requireService(String service) {
        ServiceState s = services.get(service);
        if (s == null) throw new IllegalArgumentException("unknown service: " + service);
        return s;
    }
}
