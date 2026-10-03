package com.payledger.outbox;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

@Configuration(proxyBeanMethods = false)
class OutboxObservations {

    /**
     * The relay polls five times a second, mostly finding nothing: a trace per poll would be pure noise. Each event it
     * publishes gets a span in the trace of the request that caused it instead (see {@link OutboxTracing}).
     */
    @Bean
    ObservationPredicate skipOutboxPolls() {
        return (name, context) -> !(context instanceof ScheduledTaskObservationContext task
                && task.getTargetClass() == OutboxRelayScheduler.class);
    }
}
