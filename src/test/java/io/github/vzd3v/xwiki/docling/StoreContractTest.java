package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreContractTest {
  @TempDir Path directory;

  @Test
  void conversionSurvivesRestartAndFinalInterruptedAttemptIsRecoverable() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("key", "bytes", "profile", "application/pdf", "input");
      assertEquals(1, store.claim(0, 1, "profile").attempts());
      assertNull(store.claim(0, 1, "profile"));
    }
    try (Store store = new Store(directory)) {
      Store.Job resumed = store.claim(0, 1, "profile");
      assertNotNull(resumed);
      store.completed(resumed, "recognized");
      assertEquals("recognized", store.ready("key"));
    }
  }

  @Test
  void changedProfileNeverClaimsOldOptionsAndReplacementDiscardsStaleOwner() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("old", "oldbytes", "oldprofile", "application/pdf", "oldinput");
      store.enqueue("new", "newbytes", "newprofile", "application/pdf", "newinput");
      assertNull(store.claim(0, 3, "otherprofile"));
      Store.Job old = store.claim(0, 3, "oldprofile");
      store.replaceOwners(
          "wiki:Page", List.of(new Store.Owner("wiki:Page@file", "wiki:Page", "1.1", "old")));
      store.replaceOwners(
          "wiki:Page", List.of(new Store.Owner("wiki:Page@file", "wiki:Page", "1.2", "new")));
      store.indexed(store.indexJob(0));
      store.completed(old, "obsolete text");
      assertNull(store.indexJob(0), "obsolete conversion must not index replacement owner");
      store.completed(store.claim(0, 3, "newprofile"), "current text");
      assertEquals("wiki:Page", store.indexJob(0).page());
      store.replaceOwners("wiki:Page", List.of());
      assertTrue(store.owners("wiki:Page").isEmpty());
    }
  }

  @Test
  void newerIndexGenerationSurvivesAcknowledgmentOfOlderWork() throws Exception {
    try (Store store = new Store(directory)) {
      store.requestReconcile("wiki:Page");
      Store.IndexJob old = store.indexJob(0);
      store.requestReconcile("wiki:Page");
      store.indexed(old);
      assertNotNull(store.indexJob(0));
      store.indexFailed(store.indexJob(0), "temporary", 1000);
      assertNull(store.indexJob(999));
      assertNotNull(store.indexJob(1000));
    }
  }

  @Test
  void ownerChangeRetainsIndexIntentAfterCallbackFailure() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("key", "bytes", "profile", "application/pdf", "input");
      store.requestReconcile("wiki:Page");
      Store.IndexJob before = store.indexJob(0);
      assertFalse(before.needsIndex());
      List<Store.Owner> owners =
          List.of(new Store.Owner("wiki:Page@file", "wiki:Page", "1.1", "key"));
      assertTrue(store.replaceOwners("wiki:Page", owners));
      store.indexFailed(before, "failure", 10);
      Store.IndexJob retry = store.indexJob(10);
      assertTrue(retry.needsIndex());
      assertFalse(store.replaceOwners("wiki:Page", owners));
      assertTrue(store.indexJob(10).needsIndex());
    }
  }

  @Test
  void retryAndForgetKeepOldProfileSpoolsRecoverable() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("old", "oldbytes", "oldprofile", "application/pdf", "oldinput");
      store.enqueue("new", "newbytes", "newprofile", "application/pdf", "newinput");
      store.failInactiveProfiles("newprofile");
      store.retryFailed("newprofile");
      assertEquals(List.of("old"), store.forgetFailed());
      assertNotNull(store.claim(0, 3, "newprofile"));
      assertNull(store.claim(0, 3, "oldprofile"));
    }
  }

  @Test
  void duplicateSubmissionKeepsSuccessfulTextAndBoundedRetriesAreExplicit() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("key", "bytes", "profile", "application/pdf", "input");
      Store.Job job = store.claim(0, 1, "profile");
      store.failed(job, "failure", 100, 1);
      assertEquals(1L, store.stats().get("failed"));
      assertNull(store.claim(100, 1, "profile"));
      store.retryFailed("profile");
      job = store.claim(100, 1, "profile");
      store.completed(job, "cached");
      store.enqueue("key", "bytes", "profile", "application/pdf", "input");
      assertEquals("cached", store.ready("key"));
      assertNull(store.claim(100, 1, "profile"));
    }
  }

  @Test
  void targetedCleanupGuardsAllKeysAndPreservesOtherPageOwnership() throws Exception {
    try (Store store = new Store(directory)) {
      store.enqueue("shared", "bytes", "profile", "application/pdf", "input");
      store.completed(store.claim(0, 1, "profile"), "recognized");
      store.enqueue("unused", "other", "profile", "application/pdf", "input2");
      store.completed(store.claim(0, 1, "profile"), "other text");
      var first = new Store.Owner("wiki:First@file", "wiki:First", "1.1", "shared");
      var second = new Store.Owner("wiki:Second@file", "wiki:Second", "1.1", "shared");
      store.replaceOwners("wiki:First", List.of(first));
      store.replaceOwners("wiki:Second", List.of(second));
      store.replaceOwners("wiki:First", List.of());
      assertEquals(List.of(second), store.owners("wiki:Second"));
      assertThrows(
          IllegalStateException.class, () -> store.forgetUnused(List.of("unused", "shared")));
      assertEquals(
          "other text", store.ready("unused"), "rejection must roll back the whole request");
      store.replaceOwners("wiki:Second", List.of());
      assertEquals(1, store.forgetUnused(List.of("shared")));
      assertNull(store.ready("shared"));
      assertEquals("other text", store.ready("unused"));
      assertEquals(0, store.forgetUnused(List.of("shared")));
    }
  }
}
