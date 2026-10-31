package com.example.helpdesk;

import jakarta.persistence.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Transactional(readOnly = true)
// JPA 的 EntityManager 负责对象与表之间的映射；Service 负责业务规则，仓储统一处理持久化。
public class BusinessRepository {
  @PersistenceContext EntityManager em;
  private final TransactionTemplate transactions;

  /**
   * 用途：创建编程式事务模板。
   * 输入：manager：Spring 事务管理器。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  public BusinessRepository(org.springframework.transaction.PlatformTransactionManager manager) {
    transactions = new TransactionTemplate(manager);
  }

  /**
   * 用途：在事务中执行一组数据库操作。
   * 输入：action：返回 T 的业务回调。
   * 输出：回调结果 T；异常时回滚。
   */
  @Transactional
  public <T> T tx(Supplier<T> action) {
    // TransactionTemplate 为一组写操作划定提交/回滚边界；同一事务中要么全部成功，要么回滚。
    return transactions.execute(_ -> action.get());
  }

  /**
   * 用途：按主键查找实体。
   * 输入：type：实体类型；id：可空主键。
   * 输出：实体或 null。
   */
  public <T> T find(Class<T> type, String id) {
    return id == null ? null : em.find(type, id);
  }

  /**
   * 用途：取得带悲观写锁的实体，供事务内竞争更新。
   * 输入：type：实体类型；id：主键。
   * 输出：实体或 null；锁在事务结束释放。
   */
  public <T> T lock(Class<T> type, String id) {
    // 悲观写锁持续到事务结束，用于串行处理同一行上的竞争更新；调用方必须处于事务中。
    return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
  }

  /**
   * 用途：执行参数化 JPQL 查询。
   * 输入：type：实体类型；where：服务端固定查询片段；values：绑定值。
   * 输出：类型化实体列表。
   */
  public <T> List<T> query(Class<T> type, String where, Object... values) {
    // JPQL 使用实体名和属性名；参数通过绑定传入，where 结构只允许来自服务端固定代码。
    var q = em.createQuery("select e from " + type.getSimpleName() + " e " + where, type);
    for (int i = 0; i < values.length; i++) q.setParameter(i + 1, values[i]);
    return q.getResultList();
  }

  /**
   * 用途：合并实体状态到数据库。
   * 输入：value：待保存实体。
   * 输出：托管实体；持久化新增或更新。
   */
  @Transactional
  public <T> T save(T value) {
    return em.merge(value);
  }

  /**
   * 用途：删除实体记录。
   * 输入：value：待删除实体。
   * 输出：void；执行数据库删除。
   */
  @Transactional
  public void remove(Object value) {
    em.remove(em.contains(value) ? value : em.merge(value));
  }

  /**
   * 用途：按条件批量删除实体。
   * 输入：entity：实体名称；where：固定条件；values：绑定值。
   * 输出：受影响记录数。
   */
  @Transactional
  public int delete(String entity, String where, Object... values) {
    var q = em.createQuery("delete from " + entity + " e " + where);
    for (int i = 0; i < values.length; i++) q.setParameter(i + 1, values[i]);
    return q.executeUpdate();
  }

  /**
   * 用途：探测数据库能否执行查询。
   * 输入：无参数。
   * 输出：成功返回 true，失败抛异常。
   */
  public boolean databaseReady() {
    em.createNativeQuery("select 1", Integer.class).getSingleResult();
    return true;
  }
}
