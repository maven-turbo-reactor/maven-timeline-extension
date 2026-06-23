package com.github.seregamorph.maven.timeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Sergey Chernov
 */
public final class ClasspathResources {

    static byte[] readBytes(String resource) throws IllegalStateException {
        Objects.requireNonNull(resource, "resource");
        List<URL> urls = getResourceURLs(resource);

        if (urls.isEmpty()) {
            throw new IllegalStateException(String.format("Missing resource [%s]", resource));
        }
        if (urls.size() > 1) {
            throw new IllegalStateException(String.format("Ambiguity resource [%s]: %s", resource, urls));
        }

        URL url = urls.get(0);
        ByteArrayOutputStream bais = new ByteArrayOutputStream();
        try (InputStream in = url.openStream()) {
            int read;
            byte[] data = new byte[1024];
            while ((read = in.read(data, 0, data.length)) != -1) {
                bais.write(data, 0, read);
            }
            return bais.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Error while reading resource [%s]", resource), e);
        }
    }

    public static List<URL> getResourceURLs(String resource) {
        ClassLoader classLoader = ClasspathResources.class.getClassLoader();
        try {
            return Collections.list(classLoader.getResources(resource));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list [" + resource + "] resources", e);
        }
    }

    private ClasspathResources() {}
}
