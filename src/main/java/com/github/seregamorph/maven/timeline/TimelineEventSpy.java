package com.github.seregamorph.maven.timeline;

import java.io.File;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.EventSpy;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.ArtifactRepository;

/**
 * Intercepts Maven artifact resolver events and records the number of bytes
 * transferred to/from remote repositories into {@link ResolverIoStats}, together with
 * the transfer's start and finish so its throughput can be spread over its real
 * duration. Maven's {@code EventSpyDispatcher} bridges Aether {@link RepositoryEvent}s
 * to every registered {@link EventSpy}, so this captures real resolver I/O regardless
 * of Maven 3 vs 4.
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
                case ARTIFACT_DOWNLOADING:
                case METADATA_DOWNLOADING:
                    resolverIoStats.startTransfer(key(repositoryEvent, false));
                    break;
                case ARTIFACT_DOWNLOADED:
                case METADATA_DOWNLOADED:
                    resolverIoStats.finishTransfer(key(repositoryEvent, false),
                        fileLength(repositoryEvent.getFile()), false);
                    break;
                case ARTIFACT_DEPLOYING:
                case METADATA_DEPLOYING:
                    resolverIoStats.startTransfer(key(repositoryEvent, true));
                    break;
                case ARTIFACT_DEPLOYED:
                case METADATA_DEPLOYED:
                    resolverIoStats.finishTransfer(key(repositoryEvent, true),
                        fileLength(repositoryEvent.getFile()), true);
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

    private static String key(RepositoryEvent event, boolean upload) {
        Artifact artifact = event.getArtifact();
        Metadata metadata = event.getMetadata();
        ArtifactRepository repository = event.getRepository();
        String subject = artifact != null ? artifact.toString() : String.valueOf(metadata);
        String repositoryId = repository != null ? repository.getId() : "";
        return (upload ? "up|" : "down|") + repositoryId + '|' + subject;
    }

    private static long fileLength(File file) {
        return file != null && file.isFile() ? file.length() : 0L;
    }
}