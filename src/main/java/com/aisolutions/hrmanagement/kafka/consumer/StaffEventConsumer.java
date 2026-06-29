package com.aisolutions.hrmanagement.kafka.consumer;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.aisolutions.hrmanagement.kafka.events.StaffEvent;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;

@ApplicationScoped
public class StaffEventConsumer {

  // StaffRead staffRead;

  // NOTE: commented out copy to own db because it is bad and can lead to stale
  // data. Kept the event just for easy implementation of kafka in the future and
  // kafka would complain if theres no consumer channel
  @Incoming("staff-events")
  @WithTransaction
  public Uni<Void> consume(StaffEvent evt) {
    // if (evt == null || evt.eventType == null || evt.eventType.isBlank()) {
    // return Uni.createFrom().voidItem(); // drop poison pill
    // }
    // System.out.println("Received StaffEvent: " + evt.staffCode + " - " +
    // evt.staffName);
    // return switch (evt.eventType.trim()) {
    // case "CREATED", "UPDATED" ->
    // StaffRead.upsert(evt.staffUuid, evt.staffCode, evt.staffName,
    // evt.emailString);
    // case "DELETED" ->
    // StaffRead.deleteById(evt.staffUuid).replaceWithVoid();
    // default -> Uni.createFrom().voidItem();
    return Uni.createFrom().voidItem();
  };

}
