package com.chris.chat;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LoggingHandler;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author chrischen
 */
public class NettyChatServer {

    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) {
        //1. 声明线程池
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            //2. 服务端引导器
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap
                    //3. 设置线程池
                    .group(bossGroup, workerGroup)
                    //4. 设置ServerSocketChannel类型
                    .channel(NioServerSocketChannel.class)
                    //5. 设置参数
                    .option(ChannelOption.SO_BACKLOG, 100)
                    //6. 设置ServerSocketChannel对应的handler，只能设置一个
                    .handler(new LoggingHandler())
                    //7. 设置SocketChannel对应的handler
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            ChannelPipeline pipeline = socketChannel.pipeline();
                            //可以添加多个子handler
                            pipeline.addLast(new LoggingHandler());
                            //只需要改这一个地方
                            pipeline.addLast(new ChatNettyHandler());
                        }
                    });
            //8. 绑定端口
            ChannelFuture f = serverBootstrap.bind(PORT).sync();
            //9. 等待服务端监听端口关闭，这里会阻塞主线程
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //10. 优雅的关闭两个线程池
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class ChatNettyHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("one conn active: " + ctx.channel());
            // channel是在ServerBootstrapAcceptor中放到EventLoopGroup中的
            ChatHolder.join((SocketChannel) ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf) throws Exception {
            byte[] bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            System.out.println(content);

            if ("quit\r\n".equals(content)) {
                channelHandlerContext.channel().close();
            } else {
                ChatHolder.propagate((SocketChannel) channelHandlerContext.channel(), content);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("one conn inactive: " + ctx.channel());
            ChatHolder.quit((SocketChannel) ctx.channel());
        }
    }

    private static class ChatHolder {

        static final Map<SocketChannel, String> USER_MAP = new ConcurrentHashMap<>();

        /**
         * 加入群聊
         *
         * @param socketChannel
         */
        public static void join(SocketChannel socketChannel) {
            // 有人加入就给他分配一个id
            String userId = "用户" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
            send(socketChannel, "您的id为：" + userId + "\n\r");

            for (SocketChannel channel : USER_MAP.keySet()) {
                send(channel, userId + " 加入了群聊" + "\n\r");
            }

            // 将当前用户加入到map中
            USER_MAP.put(socketChannel, userId);
        }

        /**
         * 退出群聊
         *
         * @param socketChannel
         */
        public static void quit(SocketChannel socketChannel) {
            String userId = USER_MAP.get(socketChannel);
            send(socketChannel, "您退出了群聊" + "\n\r");
            USER_MAP.remove(socketChannel);

            for (SocketChannel channel : USER_MAP.keySet()) {
                if (channel != socketChannel) {
                    send(channel, userId + " 退出了群聊" + "\n\r");
                }
            }
        }

        /**
         * 扩散说话的内容
         *
         * @param socketChannel
         * @param content
         */
        public static void propagate(SocketChannel socketChannel, String content) {
            String userId = USER_MAP.get(socketChannel);
            for (SocketChannel channel : USER_MAP.keySet()) {
                if (channel != socketChannel) {
                    send(channel, userId + ": " + content);
                }
            }
        }

        /**
         * 发送消息
         *
         * @param socketChannel
         * @param msg
         */
        static void send(SocketChannel socketChannel, String msg) {
            try {
                ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
                ByteBuf writeBuffer = allocator.buffer(msg.getBytes().length);
                writeBuffer.writeCharSequence(msg, StandardCharsets.UTF_8);
                socketChannel.writeAndFlush(writeBuffer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
