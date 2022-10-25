package data.scripts.net.io.udp;

import data.scripts.net.io.CompressionUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class DatagramDecoder extends MessageToMessageDecoder<DatagramPacket> {

    @Override
    protected void decode(ChannelHandlerContext context, DatagramPacket in, List<Object> out) throws Exception {
        ByteBuf content = in.content();
        int size = content.readInt();
        int sizeCompressed = content.readInt();
        int connectionID = content.readInt();

        byte[] bytes = new byte[sizeCompressed];
        content.readBytes(bytes);

        byte[] decompressed = CompressionUtils.inflate(bytes, size);

        DatagramUnpacker.DatagramBytes d = new DatagramUnpacker.DatagramBytes(bytes, connectionID);
        out.add(d);
    }
}
