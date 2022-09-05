package data.scripts.net.io.tcp;

import data.scripts.net.io.UnpackAlgorithm;
import data.scripts.net.io.Unpacked;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.InetSocketAddress;
import java.util.List;

public class BufferUnpacker extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;

        Unpacked result = UnpackAlgorithm.unpack(
                in,
                (InetSocketAddress) channelHandlerContext.channel().remoteAddress(),
                (InetSocketAddress) channelHandlerContext.channel().localAddress()
        );

        out.add(result);
    }
}
