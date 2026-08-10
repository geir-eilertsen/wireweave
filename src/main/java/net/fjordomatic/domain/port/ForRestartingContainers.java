package net.fjordomatic.domain.port;

public interface ForRestartingContainers {
    void restartContainer(String containerName);
}
