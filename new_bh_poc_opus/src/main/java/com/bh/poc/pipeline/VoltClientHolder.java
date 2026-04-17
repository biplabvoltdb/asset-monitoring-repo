package com.bh.poc.pipeline;

import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;

/**
 * Process-wide singleton VoltDB client used by the VoltSP Java processor.
 * <p>
 * The processor runs inside the VoltSP worker; it reads the servers/user/password
 * from the same runtime-config keys that configure the "voltdb" voltdb-client resource,
 * so the processor and the resource talk to the same VoltDB cluster.
 * <p>
 * See {@code config/pipeline-config.yaml} - the voltdb-client resource block:
 * <pre>
 * resources:
 *   - name: voltdb
 *     voltdb-client:
 *       servers: [ "host:21212" ]
 * </pre>
 */
public final class VoltClientHolder {
    private static volatile Client CLIENT;

    private VoltClientHolder() {}

    public static Client get(String servers, String user, String password) {
        Client c = CLIENT;
        if (c != null) return c;
        synchronized (VoltClientHolder.class) {
            if (CLIENT != null) return CLIENT;
            try {
                ClientConfig cfg = (user == null || user.isEmpty())
                    ? new ClientConfig()
                    : new ClientConfig(user, password);
                cfg.setTopologyChangeAware(true);
                Client client = ClientFactory.createClient(cfg);
                for (String s : servers.split(",")) client.createConnection(s.trim());
                CLIENT = client;
                return client;
            } catch (Exception e) {
                throw new RuntimeException("Failed to open VoltDB client to " + servers, e);
            }
        }
    }

    public static void close() {
        try { if (CLIENT != null) CLIENT.close(); } catch (Exception ignore) {}
        CLIENT = null;
    }
}
