package io.jenkins.plugins.workfloworas;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

@NullMarked
public class ZotContainer extends GenericContainer<ZotContainer> {

    public ZotContainer() {
        super("ghcr.io/project-zot/zot-linux-amd64:v2.1.5");
        addExposedPort(5000);
        setWaitStrategy(Wait.forHttp("/v2/_catalog").forPort(5000).forStatusCode(200));

        try {

            // Zot config file
            Path configFile = Files.createTempFile("zot-config", ".json");
            // language=JSON
            String configJson =
                    """
                    {
                      "storage": { "rootDirectory": "/var/lib/registry" },
                      "http": {
                        "address": "0.0.0.0",
                        "port": 5000
                      },
                      "extensions": {
                        "search": { "enable": true }
                      }
                    }
              """;

            Files.writeString(configFile, configJson);

            // Copy it into the container
            withCopyFileToContainer(
                    MountableFile.forHostPath(configFile.toAbsolutePath().toString()), "/etc/zot/config.json");

        } catch (Exception e) {
            throw new RuntimeException("Failed to create auth.htpasswd", e);
        }
    }

    /**
     * Get the registry URL
     * @return The registry URL
     */
    public String getRegistry() {
        return getHost() + ":" + getMappedPort(5000);
    }
}
