package com.tu.deviceplatform.nettylab.adapter;

import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 仅用于本机实验的行文本回显设备；客户端共享一条 I/O 与一条 worker。 */
public final class LocalTcpLab implements AutoCloseable {
    private final NioEventLoopGroup acceptor =
            new NioEventLoopGroup(1, new DefaultThreadFactory("sim-accept"));
    private final NioEventLoopGroup deviceIo =
            new NioEventLoopGroup(1, new DefaultThreadFactory("sim-device-io"));
    private final NioEventLoopGroup clientIo =
            new NioEventLoopGroup(1, new DefaultThreadFactory("adapter-io"));
    private final DefaultEventExecutorGroup callbacks =
            new DefaultEventExecutorGroup(1, new DefaultThreadFactory("adapter-worker"));
    private final List<Channel> channels = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> handlerFailure = new CompletableFuture<>();
    private Channel listener;

    public LocalTcpLab() throws Exception {
        try {
            ChannelFuture binding = new ServerBootstrap().group(acceptor, deviceIo)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channels.add(channel);
                            installTextCodec(channel.pipeline());
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext context, String text) {
                                    context.writeAndFlush(text + "\n");
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                    handlerFailure.completeExceptionally(cause);
                                    context.close();
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0);
            LabSupport.await(binding);
            listener = binding.channel();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    public Channel connect(String device, Consumer<String> receiver) throws Exception {
        ChannelFuture connecting = new Bootstrap().group(clientIo).channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        channels.add(socket);
                        installTextCodec(socket.pipeline());
                        socket.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext context, String text) {
                                LabSupport.log(device, "收到 " + text + " channel=" + socket.id().asShortText());
                                receiver.accept(text);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                handlerFailure.completeExceptionally(cause);
                                context.close();
                            }
                        });
                    }
                }).connect((InetSocketAddress) listener.localAddress());
        LabSupport.await(connecting);
        Channel channel = connecting.channel();
        LabSupport.log(device, "已建连 channel=" + channel.id().asShortText());
        return channel;
    }

    private static void installTextCodec(ChannelPipeline pipeline) {
        pipeline.addLast(new LineBasedFrameDecoder(1024));
        pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
        pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
    }

    public static void send(Channel channel, String text) throws Exception {
        LabSupport.await(channel.writeAndFlush(text + "\n"));
    }

    public EventLoop io() {
        return clientIo.next();
    }

    public EventExecutor worker() {
        return callbacks.next();
    }

    public void assertHealthy() throws Exception {
        if (handlerFailure.isDone()) {
            LabSupport.await(handlerFailure);
        }
    }

    /** 可从 I/O 或 worker 发起，调用者决定是否等待，绝不在当前执行器内阻塞。 */
    public CompletableFuture<Void> shutdownClientAsync() {
        CompletableFuture<Void> ioStopped = new CompletableFuture<>();
        CompletableFuture<Void> workerStopped = new CompletableFuture<>();
        clientIo.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(result -> {
            if (result.isSuccess()) {
                ioStopped.complete(null);
            } else {
                ioStopped.completeExceptionally(result.cause());
            }
        });
        callbacks.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(result -> {
            if (result.isSuccess()) {
                workerStopped.complete(null);
            } else {
                workerStopped.completeExceptionally(result.cause());
            }
        });
        return CompletableFuture.allOf(ioStopped, workerStopped);
    }

    @Override
    public void close() {
        try {
            if (listener != null) {
                closeChannel(listener);
            }
            for (Channel channel : channels) {
                closeChannel(channel);
            }
        } finally {
            Future<?> ioClosed = clientIo.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> workerClosed = callbacks.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> deviceClosed = deviceIo.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> acceptorClosed = acceptor.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            awaitCleanup(ioClosed);
            awaitCleanup(workerClosed);
            awaitCleanup(deviceClosed);
            awaitCleanup(acceptorClosed);
        }
        LabSupport.check(channels.stream().noneMatch(Channel::isOpen), "仍有 TCP Channel 未关闭");
        LabSupport.check(clientIo.isTerminated() && callbacks.isTerminated()
                && deviceIo.isTerminated() && acceptor.isTerminated(), "实验执行器未退出");
        LabSupport.log("资源", "TCP 与客户端／模拟设备端执行器均已退出");
    }

    private static void closeChannel(Channel channel) {
        // 关闭实验已停止客户端执行器；已关闭的 Channel 应等待既有结果，不能再提交 close 任务。
        if (channel.isOpen()) {
            awaitCleanup(channel.close());
        } else {
            awaitCleanup(channel.closeFuture());
        }
    }

    private static void awaitCleanup(Future<?> future) {
        LabSupport.check(future.awaitUninterruptibly(LabSupport.WAIT_SECONDS, TimeUnit.SECONDS),
                "资源释放超时");
        if (!future.isSuccess()) {
            throw new IllegalStateException("资源释放失败", future.cause());
        }
    }
}
