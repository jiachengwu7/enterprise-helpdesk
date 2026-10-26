package com.example.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class LocksTest {
  @Test
  void nestedChatAndDocumentLocksCannotCollideAcrossNamespaces() {
    Locks locks = new Locks();
    var chatLocks = new HashSet<Object>();
    var documentLocks = new HashSet<Object>();
    for (int i = 0; i < 1000; i++) {
      chatLocks.add(locks.forKey("chat:" + i));
      documentLocks.add(locks.forKey("doc:" + i));
    }
    chatLocks.retainAll(documentLocks);
    assertThat(chatLocks).isEmpty();
  }
}
