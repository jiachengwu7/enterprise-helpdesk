package com.example.helpdesk;

import jakarta.persistence.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Transactional(readOnly = true)
public class BusinessRepository {
  @PersistenceContext EntityManager em;
  private final TransactionTemplate transactions;

  public BusinessRepository(org.springframework.transaction.PlatformTransactionManager manager) {
    transactions = new TransactionTemplate(manager);
  }

  @Transactional
  public <T> T tx(Supplier<T> action) {
    return transactions.execute(_ -> action.get());
  }

  public <T> T find(Class<T> type, String id) {
    return id == null ? null : em.find(type, id);
  }

  public <T> T lock(Class<T> type, String id) {
    return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
  }

  public <T> List<T> query(Class<T> type, String where, Object... values) {
    var q = em.createQuery("select e from " + type.getSimpleName() + " e " + where, type);
    for (int i = 0; i < values.length; i++) q.setParameter(i + 1, values[i]);
    return q.getResultList();
  }

  @Transactional
  public <T> T save(T value) {
    return em.merge(value);
  }

  @Transactional
  public void remove(Object value) {
    em.remove(em.contains(value) ? value : em.merge(value));
  }

  @Transactional
  public int delete(String entity, String where, Object... values) {
    var q = em.createQuery("delete from " + entity + " e " + where);
    for (int i = 0; i < values.length; i++) q.setParameter(i + 1, values[i]);
    return q.executeUpdate();
  }

  public boolean databaseReady() {
    em.createNativeQuery("select 1", Integer.class).getSingleResult();
    return true;
  }
}
