package io.netty.handler.codec.http;

import java.util.HashMap;
import java.util.Map;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Inbound handler that converts HttpRequest to Routed and passes Routed to the
 * matched handler.
 */
@ChannelHandler.Sharable
public class Router extends SimpleChannelInboundHandler<HttpRequest> {
  public static final String ROUTER_HANDLER_NAME = Router.class.getName() + "_ROUTER_HANDLER";
  public static final String ROUTED_HANDLER_NAME = Router.class.getName() + "_ROUTED_HANDLER";

  protected final Map<HttpMethod, jauter.Router<Object>> routers =
      new HashMap<HttpMethod, jauter.Router<Object>>();

  protected final jauter.Router<Object> anyMethodRouter =
      new jauter.Router<Object>();

  protected final EventExecutorGroup group;

  protected final ChannelInboundHandler handler404;

  //----------------------------------------------------------------------------

  public Router() {
    this(null, new DefaultHandler404());
  }

  public Router(ChannelInboundHandler handler404) {
    this(null, handler404);
  }

  public Router(EventExecutorGroup group) {
    this(group, new DefaultHandler404());
  }

  public Router(EventExecutorGroup group, ChannelInboundHandler handler404) {
    this.group      = group;
    this.handler404 = handler404;
  }

  /**
   * Should be used to add the router to pipeline:
   * channel.pipeline().addLast(router.name(), router)
   */
  public String name() {
    return ROUTER_HANDLER_NAME;
  }

  //----------------------------------------------------------------------------

  public Router pattern(HttpMethod method, String path, ChannelInboundHandler handlerInstance) {
    getRouter(method).pattern(path, handlerInstance);
    return this;
  }

  public Router pattern(HttpMethod method, String path, Class<? extends ChannelInboundHandler> handlerClass) {
    getRouter(method).pattern(path, handlerClass);
    return this;
  }

  public Router patternFirst(HttpMethod method, String path, ChannelInboundHandler handlerInstance) {
    getRouter(method).patternFirst(path, handlerInstance);
    return this;
  }

  public Router patternFirstFirst(HttpMethod method, String path, Class<? extends ChannelInboundHandler> handlerClass) {
    getRouter(method).patternFirst(path, handlerClass);
    return this;
  }

  public Router patternLast(HttpMethod method, String path, ChannelInboundHandler handlerInstance) {
    getRouter(method).patternLast(path, handlerInstance);
    return this;
  }

  public Router patternLast(HttpMethod method, String path, Class<? extends ChannelInboundHandler> handlerClass) {
    getRouter(method).patternLast(path, handlerClass);
    return this;
  }

  private jauter.Router<Object> getRouter(HttpMethod method) {
    if (method == null) return anyMethodRouter;

    jauter.Router<Object> jr = routers.get(method);
    if (jr == null) {
      jr = new jauter.Router<Object>();
      routers.put(method, jr);
    }
    return jr;
  }

  //----------------------------------------------------------------------------

  public void removeTarget(Object target) {
    for (jauter.Router<Object> jr : routers.values()) jr.removeTarget(target);
    anyMethodRouter.removeTarget(target);
  }

  public void removePath(String path) {
    for (jauter.Router<Object> jr : routers.values()) jr.removePath(path);
    anyMethodRouter.removePath(path);
  }

  //----------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest req) throws InstantiationException, IllegalAccessException {
    if (HttpHeaders.is100ContinueExpected(req)) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
      return;
    }

    HttpMethod            method  = req.getMethod();
    jauter.Router<Object> jrouter = (method == null)? anyMethodRouter : routers.get(method);
    ChannelInboundHandler handler = handler404;

    String                uri     = req.getUri();
    QueryStringDecoder    qsd     = new QueryStringDecoder(uri);
    Map<String, String>   pathParams;

    // Create handler
    if (jrouter != null) {
      jauter.Routed<Object> jrouted = jrouter.route(qsd.path());
      if (jrouted != null) {
        // Create handler
        Object target = jrouted.target();
        if (target instanceof ChannelInboundHandler) {
          handler = (ChannelInboundHandler) target;
        } else {
          Class<? extends ChannelInboundHandler> klass = (Class<? extends ChannelInboundHandler>) target;
          handler = klass.newInstance();
        }

        pathParams = jrouted.params();
      } else {
        pathParams = new HashMap<String, String>();
      }
    } else {
      pathParams = new HashMap<String, String>();
    }

    ReferenceCountUtil.retain(req);
    Routed routed = new Routed(req, qsd.path(), pathParams, qsd.parameters());

    // The handler may have been added (keep alive)
    ChannelPipeline pipeline     = ctx.pipeline();
    ChannelHandler  addedHandler = pipeline.get(ROUTED_HANDLER_NAME);
    if (handler != addedHandler) {
      if (addedHandler == null) {
        if (group == null)
          pipeline.addAfter(ROUTER_HANDLER_NAME, ROUTED_HANDLER_NAME, handler);
        else
          pipeline.addAfter(group, ROUTER_HANDLER_NAME, ROUTED_HANDLER_NAME, handler);
      } else {
        pipeline.replace(addedHandler, ROUTED_HANDLER_NAME, handler);
      }
    }

    // Pass to the routed handler
    ctx.fireChannelRead(routed);
  }

  //----------------------------------------------------------------------------
  // Reverse routing.

  public String path(HttpMethod method, ChannelInboundHandler handlerInstance, Object... params) {
    return _path(method, handlerInstance, params);
  }

  public String path(HttpMethod method, Class<? extends ChannelInboundHandler> handlerClass, Object... params) {
    return _path(method, handlerClass, params);
  }

  private String _path(HttpMethod method, Object target, Object... params) {
    jauter.Router<Object> router = (method == null)? anyMethodRouter : routers.get(method);
    return (router == null)? null : router.path(target);
  }

  //----------------------------------------------------------------------------
  // Utilities to write.

  public static ChannelFuture keepAliveWriteAndFlush(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
    if (!HttpHeaders.isKeepAlive(req)) {
      return ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    } else {
      res.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      return ctx.writeAndFlush(res);
    }
  }

  public static ChannelFuture keepAliveWriteAndFlush(Channel ch, HttpRequest req, HttpResponse res) {
    if (!HttpHeaders.isKeepAlive(req)) {
      return ch.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    } else {
      res.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      return ch.writeAndFlush(res);
    }
  }
}
