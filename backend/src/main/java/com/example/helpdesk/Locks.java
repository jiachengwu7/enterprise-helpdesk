package com.example.helpdesk;

import org.springframework.stereotype.Component;

/** Bounded locks for this single-instance local deployment, avoiding unbounded user-key maps. */
@Component
public class Locks {
  private final Object[][] domains = new Object[3][256];

  public Locks() {
    for (Object[] stripes : domains) java.util.Arrays.setAll(stripes, _ -> new Object());
  }

  public Object forKey(String key) {
    // Chat calls acquire document locks, so the namespaces must never share a monitor.
    int domain = key.startsWith("chat:") ? 0 : key.startsWith("doc:") ? 1 : 2;
    Object[] stripes = domains[domain];
    return stripes[Math.floorMod(key.hashCode(), stripes.length)];
  }
}
