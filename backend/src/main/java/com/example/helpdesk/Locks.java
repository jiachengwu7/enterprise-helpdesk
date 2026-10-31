package com.example.helpdesk;

import org.springframework.stereotype.Component;

/** Bounded locks for this single-instance local deployment, avoiding unbounded user-key maps. */
@Component
public class Locks {
  private final Object[][] domains = new Object[3][256];

  /**
   * 用途：预分配不同业务域的固定数量锁，限制锁对象占用。
   * 输入：无参数。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  public Locks() {
    for (Object[] stripes : domains) java.util.Arrays.setAll(stripes, _ -> new Object());
  }

  /**
   * 用途：取得业务键对应的进程内锁。
   * 输入：key：业务锁键。
   * 输出：可用于 synchronized 的锁对象；不是跨进程锁。
   */
  public Object forKey(String key) {
    // Chat calls acquire document locks, so the namespaces must never share a monitor.
    int domain = key.startsWith("chat:") ? 0 : key.startsWith("doc:") ? 1 : 2;
    Object[] stripes = domains[domain];
    return stripes[Math.floorMod(key.hashCode(), stripes.length)];
  }
}
