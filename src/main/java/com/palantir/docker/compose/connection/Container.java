/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.docker.compose.connection;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.jayway.awaitility.Awaitility;
import com.palantir.docker.compose.execution.DockerCompose;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.hamcrest.core.Is.is;

public class Container {

    private static final Logger log = LoggerFactory.getLogger(Container.class);

    private final String containerName;
    private final DockerCompose dockerComposeProcess;

    private final Supplier<Ports> portMappings = Suppliers.memoize(this::getDockerPorts);

    public Container(String containerName, DockerCompose dockerComposeProcess) {
        this.containerName = containerName;
        this.dockerComposeProcess = dockerComposeProcess;
    }

    public String getContainerName() {
        return containerName;
    }

    public boolean waitForPorts(Duration timeout) {
        try {
            Ports exposedPorts = portMappings.get();
            exposedPorts.waitToBeListeningWithin(timeout);
            return true;
        } catch (Exception e) {
            log.warn("Container '" + containerName + "' failed to come up: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean portIsListeningOnHttp(int internalPort, Function<DockerPort, String> urlFunction) {
        try {
            DockerPort port = portMappedInternallyTo(internalPort);
            return port.isListeningNow() && port.isHttpResponding(urlFunction);
        } catch (Exception e) {
            log.warn("Container '" + containerName + "' failed to come up: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean waitForHttpPort(int internalPort, Function<DockerPort, String> urlFunction, Duration timeout) {
        try {
            Awaitility.await()
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .atMost(timeout.getMillis(), TimeUnit.MILLISECONDS)
                .until(() -> portIsListeningOnHttp(internalPort, urlFunction), is(true));
            return true;
        } catch (Exception e) {
            log.warn("Container '" + containerName + "' failed to come up: " + e.getMessage(), e);
            return false;
        }
    }

    public DockerPort portMappedExternallyTo(int externalPort) throws IOException, InterruptedException {
        return portMappings.get()
                           .stream()
                           .filter(port -> port.getExternalPort() == externalPort)
                           .findFirst()
                           .orElseThrow(() -> new IllegalArgumentException("No port mapped externally to '" + externalPort + "' for container '" + containerName + "'"));
    }

    public DockerPort portMappedInternallyTo(int internalPort) throws IOException, InterruptedException {
        return portMappings.get()
                           .stream()
                           .filter(port -> port.getInternalPort() == internalPort)
                           .findFirst()
                           .orElseThrow(() -> new IllegalArgumentException("No internal port '" + internalPort + "' for container '" + containerName + "'"));
    }

    private Ports getDockerPorts() {
        try {
            return dockerComposeProcess.ports(containerName);
        } catch (IOException | InterruptedException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Container container = (Container) o;
        return Objects.equals(containerName, container.containerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerName);
    }

    @Override
    public String toString() {
        return "Container{" +
                "containerName='" + containerName + '\'' +
                '}';
    }

    public boolean areAllPortsOpen() {
        long numberOfUnavailablePorts = portMappings.get().stream()
                .filter(port -> !port.isListeningNow())
                .count();

        return numberOfUnavailablePorts == 0;
    }
}
