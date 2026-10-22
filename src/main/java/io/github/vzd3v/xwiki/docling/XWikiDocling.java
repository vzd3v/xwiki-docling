package io.github.vzd3v.xwiki.docling;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.localization.LocaleUtils;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.event.Event;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

/** XWiki lifecycle, canonical ownership, native indexing and administrator-only controls. */
@Component(roles = XWikiDocling.class)
@Named("xwiki-docling")
@Singleton
public final class XWikiDocling implements Initializable, Disposable {
  private static final String SEMANTIC_TASK = "llm_xwiki_document";
  @Inject private Provider<XWikiContext> contextProvider;
  @Inject private Logger logger;

  @Inject
  @Named("context")
  private ComponentManager componentManager;

  @Inject private Execution execution;
  @Inject private ExecutionContextManager executionContextManager;
  @Inject private AuthorizationManager authorizationManager;
  @Inject private DocumentReferenceResolver<String> resolver;

  @Inject
  @Named("withparameters")
  private EntityReferenceSerializer<String> serializer;

  private ConversionService service;
  private Config config;

  @Override
  public void initialize() throws InitializationException {
    String file = System.getProperty("xwiki.docling.config");
    if (file == null || file.isBlank()) return;
    try {
      config = Config.load(Path.of(file));
      if (config == null) return;
      service = new ConversionService(config, new DoclingConverter(config), this::indexPage);
      DoclingRuntime.install(service);
      service.start();
      logger.info("component=xwiki-docling event=started workers={}", config.workers());
    } catch (Exception e) {
      if (service != null) {
        if (DoclingRuntime.get() == service) DoclingRuntime.detach();
        try {
          service.close();
        } catch (Exception cleanup) {
          e.addSuppressed(cleanup);
        }
        service = null;
      }
      throw new InitializationException(
          "Cannot initialize XWiki Docling (configuration/store/client)", e);
    }
  }

  void onEvent(Event event, Object source, Object data) {
    if (service == null || !(source instanceof XWikiDocument document)) return;
    XWikiDocument original = document.getOriginalDocument();
    if (document.getAttachmentList().isEmpty()
        && (original == null || original.getAttachmentList().isEmpty())) return;
    XWikiDocument actual =
        event instanceof DocumentDeletedEvent && original != null ? original : document;
    try {
      service.reconcile(serializer.serialize(actual.getDocumentReferenceWithLocale()));
    } catch (Exception e) {
      logger.error("component=xwiki-docling event=reconcile_enqueue_failed", e);
    }
  }

  public Map<String, Long> status() throws Exception {
    requireAdmin();
    return service == null ? Map.of("enabled", 0L) : service.status();
  }

  public List<Map<String, Object>> failures(int limit) throws Exception {
    requireAdmin();
    requireEnabled();
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be between 1 and 100");
    return service.failures(limit);
  }

  public void reconcile(String page) throws Exception {
    requireAdmin();
    requireEnabled();
    DocumentReference ref = resolvePage(page);
    requireSameWikiAdmin(ref);
    service.reconcile(serializer.serialize(ref));
  }

  public void retryFailed() throws Exception {
    requireAdmin();
    requireEnabled();
    service.retryFailed();
  }

  private void requireEnabled() {
    if (service == null) throw new IllegalStateException("XWiki Docling is disabled");
  }

  public int forgetFailed() throws Exception {
    requireAdmin();
    requireEnabled();
    return service.forgetFailed();
  }

  private void requireAdmin() throws Exception {
    XWikiContext c = contextProvider.get();
    authorizationManager.checkAccess(
        Right.ADMIN,
        c.getUserReference(),
        new org.xwiki.model.reference.WikiReference(c.getMainXWiki()));
  }

  private void requireSameWikiAdmin(DocumentReference ref) throws Exception {
    XWikiContext c = contextProvider.get();
    authorizationManager.checkAccess(Right.ADMIN, c.getUserReference(), ref.getWikiReference());
  }

  private void indexPage(String canonicalPage, boolean needsIndex) throws Exception {
    try {
      executionContextManager.initialize(new ExecutionContext());
      XWikiContext context = contextProvider.get();
      DocumentReference ref = resolvePage(canonicalPage);
      context.setWikiId(ref.getWikiReference().getName());
      XWikiDocument page = context.getWiki().getDocument(ref, context);
      List<Store.Owner> owners = new ArrayList<>();
      if (!page.isNew()) {
        for (var attachment : page.getAttachmentList()) {
          String mime = attachment.getMimeType(context);
          if (!DoclingParser.supports(mime)) continue;
          String ownerRef =
              canonicalPage
                  + "@"
                  + java.util.Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(
                          attachment
                              .getFilename()
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          try (var bytes = attachment.getContentInputStream(context);
              var prepared = service.prepare(bytes, mime)) {
            if (prepared.deferred())
              throw new java.io.IOException(
                  "OCR spool capacity exhausted; page reconciliation remains pending");
            if (prepared.complete())
              owners.add(
                  new Store.Owner(
                      ownerRef, canonicalPage, attachment.getVersion(), prepared.key()));
          }
        }
      }
      boolean changed = service.replaceOwners(canonicalPage, owners);
      if (page.isNew()) return; // Native document deletion listeners remove their search entries.
      if (!changed && !needsIndex) return;
      Object ordinary = component("org.xwiki.search.solr.internal.api.SolrIndexer");
      ordinary
          .getClass()
          .getMethod("index", EntityReference.class, boolean.class)
          .invoke(ordinary, ref, true);
      Object ready = ordinary.getClass().getMethod("waitReady").invoke(ordinary);
      if (ready instanceof Future<?> future)
        future.get(config.timeout().toSeconds(), TimeUnit.SECONDS);
      try {
        Class<?> consumer =
            Class.forName("org.xwiki.index.TaskConsumer", true, getClass().getClassLoader());
        if (!componentManager.hasComponent(consumer, SEMANTIC_TASK)) return;
        Object tasks = component("org.xwiki.index.TaskManager");
        Object result =
            tasks
                .getClass()
                .getMethod("addTask", String.class, long.class, String.class)
                .invoke(tasks, context.getWikiId(), page.getId(), SEMANTIC_TASK);
        if (result instanceof Future<?> future)
          future.get(config.timeout().toSeconds(), TimeUnit.SECONDS);
      } catch (org.xwiki.component.manager.ComponentLookupException e) {
        // Semantic indexing is optional. Ordinary search still uses the same OCR cache.
        logger.debug("component=xwiki-docling event=semantic_index_unavailable");
      }
    } finally {
      execution.removeContext();
    }
  }

  private DocumentReference resolvePage(String reference) {
    // Split the optional locale parameter before the standard resolver, including escaped
    // separators.
    for (int i = reference.length() - 1; i >= 0; i--)
      if (reference.charAt(i) == ';') {
        int escapes = 0;
        for (int j = i - 1; j >= 0 && reference.charAt(j) == '\\'; j--) escapes++;
        if (escapes % 2 == 0)
          return new DocumentReference(
              resolver.resolve(reference.substring(0, i)),
              LocaleUtils.toLocale(reference.substring(i + 1)));
      }
    return resolver.resolve(reference);
  }

  private Object component(String name) throws Exception {
    Class<?> role = Class.forName(name, true, getClass().getClassLoader());
    return componentManager.getInstance(role);
  }

  @Override
  public void dispose() {
    ConversionService old = DoclingRuntime.detach();
    if (old != null)
      try {
        old.close();
      } catch (Exception e) {
        logger.error("component=xwiki-docling event=shutdown_failed", e);
      }
    service = null;
  }
}
