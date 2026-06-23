package com.github.seregamorph.maven.timeline;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * @author Sergey Chernov
 */
final class GroupArtifactId implements Comparable<GroupArtifactId> {

    private final String groupId;
    private final String artifactId;

    GroupArtifactId(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    @Override
    public int compareTo(GroupArtifactId that) {
        return toString().compareTo(that.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GroupArtifactId that = (GroupArtifactId) o;
        return Objects.equals(groupId, that.groupId)
            && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId);
    }

    @JsonValue
    @Override
    public String toString() {
        return groupId + ':' + artifactId;
    }
}
