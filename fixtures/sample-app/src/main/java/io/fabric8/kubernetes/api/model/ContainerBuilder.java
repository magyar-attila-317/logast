package io.fabric8.kubernetes.api.model;

import java.util.Collection;

/** Minimal stand-in for fabric8's generated ContainerBuilder fluent API. */
public class ContainerBuilder {
    public ContainerBuilder withName(String name) {
        return this;
    }

    public ContainerBuilder withImage(String image) {
        return this;
    }

    public ContainerBuilder addAllToEnv(Collection<EnvVar> env) {
        return this;
    }

    public Container build() {
        return new Container();
    }
}
