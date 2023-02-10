package data.scripts.net.io;

import cmu.CMUtils;
import data.scripts.net.data.InboundData;
import data.scripts.net.data.InstanceData;
import data.scripts.net.data.OutboundData;
import data.scripts.net.data.packables.entities.projectiles.ProjectileData;
import data.scripts.net.data.packables.entities.ships.ShipData;
import data.scripts.net.data.packables.entities.ships.VariantData;
import data.scripts.net.data.packables.metadata.ConnectionData;
import data.scripts.net.data.records.DataRecord;
import data.scripts.plugins.MPPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerConnectionWrapper extends BaseConnectionWrapper {
    private final ServerConnectionManager connectionManager;
    private final InetSocketAddress remoteAddress;

    public ServerConnectionWrapper(ServerConnectionManager connectionManager, short connectionId, InetSocketAddress remoteAddress, MPPlugin plugin) {
        super(plugin);

        this.connectionManager = connectionManager;
        this.remoteAddress = remoteAddress;
        this.connectionID = connectionId;

        connectionData = new ConnectionData(connectionId, this);
    }

    @Override
    public List<MessageContainer> getSocketMessages() throws IOException {
        if (connectionData == null) return null;

        connectionState = BaseConnectionWrapper.ordinalToConnectionState(connectionData.getConnectionState());
        clientPort = connectionData.getClientPort();

        OutboundData outbound = connectionManager.getDuplex().getOutboundSocket();

        switch (connectionState) {
            //case INITIALISATION_READY:
            case INITIALISING:
                CMUtils.getGuiDebug().putText(ServerConnectionWrapper.class, "debug" + connectionID, connectionID + ": initialising connection...");

                connectionState = ConnectionState.LOADING_READY;

                break;
            //case LOADING_READY:
            case LOADING:
                CMUtils.getGuiDebug().putText(ServerConnectionWrapper.class, "debug" + connectionID, connectionID + ": sending client data over socket...");

                Map<Short, InstanceData> variants = new HashMap<>();
                for (VariantData variantData : connectionManager.getServerPlugin().getVariantStore().getGenerated()) {
                    variants.put(variantData.getInstanceID(), variantData.sourceExecute(0f));
                }

                outbound.out.put(VariantData.TYPE_ID, variants);

                connectionState = ConnectionState.SPAWNING_READY;

                break;
            //case SPAWNING_READY:
            case SPAWNING:
                CMUtils.getGuiDebug().putText(ServerConnectionWrapper.class, "debug" + connectionID, connectionID + ": spawning entities on client...");

                Map<Short, InstanceData> ships = connectionManager.getServerPlugin().getServerShipTable().getShipsRegistered();
                outbound.out.put(ShipData.TYPE_ID, ships);

                Map<Short, InstanceData> projectiles = connectionManager.getServerPlugin().getProjectileTable().getProjectilesRegistered();
                outbound.out.put(ProjectileData.TYPE_ID, projectiles);

                connectionState = ConnectionState.SIMULATION_READY;

                break;
            //case SIMULATION_READY:
            case SIMULATING:
            case CLOSED:
            default:
                break;
        }

        Map<Short, InstanceData> instance = new HashMap<>();
        instance.put(connectionID, connectionData.sourceExecute(0f));
        outbound.out.put(ConnectionData.TYPE_ID, instance);

        return writeBuffer(outbound, connectionManager.getTick(), remoteAddress, connectionID);
    }

    @Override
    public List<MessageContainer> getDatagrams() throws IOException {
        if (connectionData == null || connectionState != ConnectionState.SIMULATING) return null;

        OutboundData outbound = connectionManager.getDuplex().getOutboundDatagram();

        switch (connectionState) {
            case INITIALISATION_READY:
            case INITIALISING:
            case LOADING_READY:
            case LOADING:
            case SPAWNING_READY:
            case SPAWNING:
            case SIMULATION_READY:
            case SIMULATING:
            case CLOSED:
            default:
                break;
        }

        return writeBuffer(outbound, connectionManager.getTick(), remoteAddress, connectionID);
    }

    @Override
    public void stop() {
        connectionState = ConnectionState.CLOSED;
        connectionManager.removeConnection(connectionID);
    }

    public void setConnectionState(ConnectionState connectionState) {
        this.connectionState = connectionState;
    }

    public void updateInbound(InboundData entities) {
        Map<Short, Map<Byte, Object>> instance = entities.in.get(ConnectionData.TYPE_ID);
        if (instance != null) connectionData.destExecute(instance.get(connectionID), connectionManager.getTick());
        entities.in.remove(ConnectionData.TYPE_ID);

        connectionManager.getDuplex().updateInbound(entities);
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }
}
