package org.example.http.framework;

import io.github.classgraph.ClassGraph;
import lombok.extern.java.Log;
import org.example.http.framework.annotation.RequestMapping;
import org.example.http.framework.exception.*;
import org.example.http.framework.guava.Bytes;
import org.example.http.framework.resolver.argument.HandlerMethodArgumentResolver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Log
public class Server {
    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] CRLFCRLF = new byte[]{'\r', '\n', '\r', '\n'};
    private final static int headersLimit = 4096;
    private final static long bodyLimit = 10 * 1024 * 1024;
    private final ServerSocket serverSocket;
    private final ExecutorService service = Executors.newFixedThreadPool(64, r -> {
        final var thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    // GET, "/search", handler
    private final Map<String, Map<String, HandlerMethod>> routes = new HashMap<>();
    // 404 Not Found ->
    // 500 Internal Server error ->
    private final Handler notFoundHandler = (request, response) -> {
        // language=JSON
        final var body = "{\"status\": \"error\"}";
        try {
            response.write(
                    (
                            // language=HTTP
                            "HTTP/1.1 404 Not Found\r\n" +
                                    "Content-Length: " + body.length() + "\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n" +
                                    body
                    ).getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new RequestHandleException(e);
        }
    };
    private final Handler internalErrorHandler = (request, response) -> {
        // language=JSON
        final var body = "{\"status\": \"error\"}";
        try {
            response.write(
                    (
                            // language=HTTP
                            "HTTP/1.1 500 Internal Server Error\r\n" +
                                    "Content-Length: " + body.length() + "\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n" +
                                    body
                    ).getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new RequestHandleException(e);
        }
    };
    private final List<HandlerMethodArgumentResolver> argumentResolvers = new ArrayList<>();

    public Server(int port) {
        try {
            this.serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new ServerException(e);
        }
    }

    public void get(String path, Handler handler) {
        registerHandler(HttpMethods.GET, path, handler);
    }

    public void post(String path, Handler handler) {
        registerHandler(HttpMethods.POST, path, handler);
    }

    public void autoRegisterHandlers(String pkg) {
        try (final var scanResult = new ClassGraph().enableAllInfo().acceptPackages(pkg).scan()) {
            for (final var classInfo : scanResult.getClassesWithMethodAnnotation(RequestMapping.class.getName())) {
                final var handler = classInfo.loadClass().getConstructor().newInstance();
                for (final var method : handler.getClass().getMethods()) {
                    if (method.isAnnotationPresent(RequestMapping.class)) {
                        final RequestMapping mapping = method.getAnnotation(RequestMapping.class);

                        final var handlerMethod = new HandlerMethod(handler, method);
                        Optional.ofNullable(routes.get(mapping.method()))
                                .ifPresentOrElse(
                                        map -> map.put(mapping.path(), handlerMethod),
                                        () -> routes.put(mapping.method(), new HashMap<>(Map.of(mapping.path(), handlerMethod)))
                                );
                    }
                }
            }
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void registerHandler(String method, String path, Handler handler) {
        try {
            final var handle = handler.getClass().getMethod("handle", Request.class, OutputStream.class);
            final var handlerMethod = new HandlerMethod(handler, handle);
            Optional.ofNullable(routes.get(method))
                    .ifPresentOrElse(
                            map -> map.put(path, handlerMethod),
                            () -> routes.put(method, new HashMap<>(Map.of(path, handlerMethod)))
                    );
        } catch (NoSuchMethodException e) {
            throw new HandlerRegistrationException(e);
        }
    }

    public void addArgumentResolver(HandlerMethodArgumentResolver... resolvers) {
        argumentResolvers.addAll(List.of(resolvers));
    }

    public void listen() {
        try {
            log.log(Level.INFO, "server started at port: " + serverSocket.getLocalPort());

            while (true) {
                Socket socket = serverSocket.accept();
                service.submit(() -> handle(socket));
            }

        } catch (SocketException e) {
            log.log(Level.INFO, "Server has been stopped");

        } catch (IOException e) {
            throw new ServerException(e);
        }
    }

    public void stop() throws IOException {
        serverSocket.close();
        service.shutdownNow();
    }

    public void handle(final Socket socket) {
        try (
                socket;
                final var in = new BufferedInputStream(socket.getInputStream());
                final var out = new BufferedOutputStream(socket.getOutputStream());
        ) {
            log.log(Level.INFO, "connected: " + socket.getPort());
            final var buffer = new byte[headersLimit];
            in.mark(headersLimit);

            final var read = in.read(buffer);

            try {
                final var requestLineEndIndex = Bytes.indexOf(buffer, CRLF, 0, read) + CRLF.length;
                if (requestLineEndIndex == 1) {
                    throw new MalformedRequestException("request line end not found");
                }

                final var requestLineParts = new String(buffer, 0, requestLineEndIndex).trim().split(" ");
                if (requestLineParts.length != 3) {
                    throw new MalformedRequestException("request line must contains 3 parts");
                }

                final var method = requestLineParts[0];
                final var uri = requestLineParts[1];

                final Map<String, List<String>> query = new HashMap<>();
                String[] pathAndQuery = uri.split("\\?");

                if (pathAndQuery.length > 1) {
                    String[] queries = pathAndQuery[1].split("&");
                    query.putAll(parse(queries));
                }


                final var headersEndIndex = Bytes.indexOf(buffer, CRLFCRLF, requestLineEndIndex, read) + CRLFCRLF.length;
                if (headersEndIndex == 3) {
                    throw new MalformedRequestException("headers too big");
                }

                var lastIndex = requestLineEndIndex;
                final var headers = new HashMap<String, String>();
                while (lastIndex < headersEndIndex - CRLF.length) {
                    final var headerEndIndex = Bytes.indexOf(buffer, CRLF, lastIndex, headersEndIndex) + CRLF.length;
                    if (headerEndIndex == 1) {
                        throw new MalformedRequestException("can't find header end index");
                    }
                    final var header = new String(buffer, lastIndex, headerEndIndex - lastIndex);
                    final var headerParts = Arrays.stream(header.split(":", 2))
                            .map(String::trim)
                            .collect(Collectors.toList());

                    if (headerParts.size() != 2) {
                        throw new MalformedRequestException("Invalid header: " + header);
                    }

                    headers.put(headerParts.get(0), headerParts.get(1));
                    lastIndex = headerEndIndex;
                }

                final var contentLength = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));

                if (contentLength > bodyLimit) {
                    throw new RequestBodyTooLarge();
                }

                in.reset();
                in.skipNBytes(headersEndIndex);
                final var body = in.readNBytes(contentLength);

                final var contentType = headers.get("Content-Type");
                final Map<String, List<String>> form = new HashMap<>();
                if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
                    String bodyByString = new String(body, StandardCharsets.UTF_8);
                    String[] bodyParts = bodyByString.split("&");
                    if (bodyParts.length > 1) {
                        form.putAll(parse(bodyParts));
                    }
                }


                final var request = Request.builder()
                        .method(method)
                        .path(uri)
                        .headers(headers)
                        .query(query)
                        .body(body)
                        .form(form)
                        .build();

                final var response = out;

                final var handlerMethod = Optional.ofNullable(routes.get(request.getMethod()))
                        .map(o -> o.get(request.getPath()))
                        .orElse(new HandlerMethod(notFoundHandler, notFoundHandler.getClass().getMethod("handle", Request.class, OutputStream.class)));

                try {
                    final var invokableMethod = handlerMethod.getMethod();
                    final var invokableHandler = handlerMethod.getHandler();

                    final var arguments = new ArrayList<>(invokableMethod.getParameterCount());
                    for (final var parameter : invokableMethod.getParameters()) {
                        var resolved = false;
                        for (final var argumentResolver : argumentResolvers) {
                            if (!argumentResolver.supportsParameter(parameter)) {
                                continue;
                            }

                            final var argument = argumentResolver.resolveArgument(parameter, request, response);
                            arguments.add(argument);
                            resolved = true;
                            break;
                        }
                        if (!resolved) {
                            throw new UnsupportedParameterException(parameter.getType().getName());
                        }
                    }

                    invokableMethod.invoke(invokableHandler, arguments.toArray());
                } catch (Exception e) {
                    internalErrorHandler.handle(request, response);
                }
            } catch (MalformedRequestException e) {
                // language=HTML
                final var html = "<h1>Mailformed request</h1>";
                out.write(
                        (
                                // language=HTTP
                                "HTTP/1.1 400 Bad Request\r\n" +
                                        "Server: nginx\r\n" +
                                        "Content-Length: " + html.length() + "\r\n" +
                                        "Content-Type: text/html; charset=UTF-8\r\n" +
                                        "Connection: close\r\n" +
                                        "\r\n" +
                                        html
                        ).getBytes(StandardCharsets.UTF_8)
                );
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, List<String>> parse(String[] strings) {
        Map<String, List<String>> map = new HashMap<>();
        for (String part : strings) {
            String[] parts = part.split("=");
            if (map.containsKey(parts[0])) {
                map.get(parts[0]).add(parts[1]);
            } else {
                map.put(
                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        List.of(URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                );
            }
        }
        return map;
    }
}