package data.scripts.net.io.udp.client;

import data.scripts.net.data.BaseRecord;
import data.scripts.net.io.ClientConnectionWrapper;
import data.scripts.net.io.Unpacked;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Map;

public class ClientInboundHandler extends SimpleChannelInboundHandler<Unpacked> {
    private final ClientConnectionWrapper connection;

    public ClientInboundHandler(ClientConnectionWrapper connection) {
        this.connection = connection;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Unpacked in) throws Exception {
        int serverTick = in.getTick();
        //Console.showMessage("Received UDP unpacked with tick: " + serverTick);

        // DISCARD WHILE DEBUG
        Map<Integer, Map<Integer, Map<Integer, BaseRecord<?>>>> entities = in.getUnpacked();

        connection.updateInbound(entities, serverTick);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.err.println(cause.getMessage());
        ctx.close();
    }
}
