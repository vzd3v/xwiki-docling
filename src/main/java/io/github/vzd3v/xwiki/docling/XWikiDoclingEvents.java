package io.github.vzd3v.xwiki.docling;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;

/** A separate listener role delegates to the one owning runtime component. */
@Component
@Named("xwiki-docling")
@Singleton
public final class XWikiDoclingEvents implements EventListener {
  @Inject
  @Named("xwiki-docling")
  private XWikiDocling runtime;

  @Override
  public String getName() {
    return "xwiki-docling";
  }

  @Override
  public List<Event> getEvents() {
    return List.of(
        new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent());
  }

  @Override
  public void onEvent(Event event, Object source, Object data) {
    runtime.onEvent(event, source, data);
  }
}
