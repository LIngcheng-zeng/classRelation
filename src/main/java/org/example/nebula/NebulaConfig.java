package org.example.nebula;

/**
 * Connection configuration for NebulaGraph.
 */
public record NebulaConfig(
        String host,
        int port,
        String username,
        String password,
        String spaceName
) {
    public static NebulaConfig defaultLocal(String spaceName) {
        return new NebulaConfig("localhost", 9669, "root", "nebula", spaceName);
    }
}
