package data.scripts.net.connection.udp;

import data.scripts.net.connection.server.ServerConnectionWrapper;
import data.scripts.net.io.PacketContainerDecoder;
import data.scripts.net.io.PacketContainerEncoder;
import data.scripts.net.io.PacketDecoder;
import data.scripts.plugins.mpServerPlugin;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.GlobalEventExecutor;

public class NettyServer implements Runnable {
    private final int port;
    private final mpServerPlugin serverPlugin;
    private final EventLoopGroup bossLoopGroup;
    private final ChannelGroup channelGroup;

    public NettyServer(int port, mpServerPlugin serverPlugin) {
        this.port = port;
        this.serverPlugin = serverPlugin;

        bossLoopGroup = new NioEventLoopGroup();
        channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }

    @Override
    public void run() {
        try {
            runServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void runServer() {
        try {
            final Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(bossLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        private ServerConnectionWrapper connection;

                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws InterruptedException {
                            connection = serverPlugin.getNewConnection();

                            if (connection == null) {
                                throw new InterruptedException("Channel connection refused: max connections exceeded");
                            }

                            socketChannel.pipeline().addLast(
                                    new PacketContainerEncoder(),
                                    new PacketContainerDecoder(),
                                    new PacketDecoder(),
                                    new ServerChannelHandler(connection)
                            );
                        }

                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            serverPlugin.removeConnection(connection);
                        }
                    })
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .option(ChannelOption.SO_BROADCAST, true);

            ChannelFuture channelFuture = bootstrap.bind(port).sync();
            channelGroup.add(channelFuture.channel());
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            channelGroup.close();
            bossLoopGroup.shutdownGracefully();
        }
    }
}
