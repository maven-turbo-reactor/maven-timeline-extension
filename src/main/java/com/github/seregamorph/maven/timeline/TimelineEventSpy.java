package com.github.seregamorph.maven.timeline;

import java.io.File;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.EventSpy;
import org.eclipse.aether.RepositoryEvent;

/**
 * Intercepts Maven artifact resolver events and accumulates the number of bytes
 * transferred to/from remote repositories into {@link ResolverIoStats}. Maven's
 * {@code EventSpyDispatcher} bridges Aether {@link RepositoryEvent}s to every
 * registered {@link EventSpy}, so this captures real resolver I/O regardless of
 * Maven 3 vs 4.
 *
 * @author Sergey Chernov
 */
@Named
@Singleton
public class TimelineEventSpy implements EventSpy {

    private final ResolverIoStats resolverIoStats;

    @Inject
    public TimelineEventSpy(ResolverIoStats resolverIoStats) {
        this.resolverIoStats = resolverIoStats;
    }

    @Override
    public void init(Context context) {
        // no-op
    }

    @Override
    public void onEvent(Object event) {
        if (event instanceof RepositoryEvent) {
            RepositoryEvent repositoryEvent = (RepositoryEvent) event;
            switch (repositoryEvent.getType()) {
                case ARTIFACT_DOWNLOADED:
                case METADATA_DOWNLOADED:
                    resolverIoStats.addDownloaded(fileLength(repositoryEvent.getFile()));
                    break;
                case ARTIFACT_DEPLOYED:
                case METADATA_DEPLOYED:
                    resolverIoStats.addUploaded(fileLength(repositoryEvent.getFile()));
                    break;
                default:
                    // ignore the rest
            }
        }
    }

    @Override
    public void close() {
        // no-op
    }

    private static long fileLength(File file) {
        return file != null && file.isFile() ? file.length() : 0L;
    }
}
