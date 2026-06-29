
package com.aisolutions.hrmanagement.kafka.events;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StaffEvent {
  public String staffUuid;
  public String staffCode;
  public String staffName;
  public String emailString;
  public String eventType;
}
