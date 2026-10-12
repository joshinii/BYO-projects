import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class Main {
  public static void main(String[] args) {
    System.out.println("Logs from your program will appear here!");

    String directory = getDirectoryFromArgs(args);

    try {
      ServerSocket serverSocket = new ServerSocket(4221);
      serverSocket.setReuseAddress(true);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        String rootDirectory = directory;
        Thread clientThread = new Thread(() -> handleClient(clientSocket, rootDirectory));
        clientThread.start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static String getDirectoryFromArgs(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if ("--directory".equals(args[i]) && i + 1 < args.length) {
        return args[i + 1];
      }
    }
    return ".";
  }

  private static void handleClient(Socket clientSocket, String directory) {
    try {
      InputStream inputStream = clientSocket.getInputStream();
      String requestLine = readLine(inputStream);
      String method = getMethodFromRequestLine(requestLine);
      String path = getPathFromRequestLine(requestLine);
      Map<String, String> headers = readHeaders(inputStream);

      RequestContext context = new RequestContext(method, path, headers, directory, inputStream);
      Response response = buildResponse(context);

      OutputStream outputStream = clientSocket.getOutputStream();
      outputStream.write(response.toBytes());
      outputStream.flush();
      clientSocket.close();
      System.out.println("accepted new connection");
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static Response buildResponse(RequestContext context) throws IOException {
    String status = "404 Not Found";
    String responseBody = "";
    String contentType = "text/plain";
    String contentEncoding = "";

    if ("GET".equals(context.method) && "/".equals(context.path)) {
      status = "200 OK";
    } else if ("GET".equals(context.method) && context.path.startsWith("/echo/")) {
      status = "200 OK";
      responseBody = context.path.substring("/echo/".length());
    } else if ("GET".equals(context.method) && "/user-agent".equals(context.path)) {
      status = "200 OK";
      responseBody = context.headers.getOrDefault("user-agent", "");
    } else if (context.path.startsWith("/files/")) {
      String fileName = context.path.substring("/files/".length());
      Path filePath = Path.of(context.directory, fileName);

      if ("GET".equals(context.method)) {
        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
          status = "200 OK";
          responseBody = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
          contentType = "application/octet-stream";
        }
      } else if ("POST".equals(context.method)) {
        int contentLength = getContentLength(context.headers);
        byte[] requestBodyBytes = readBody(context.inputStream, contentLength);
        Files.write(filePath, requestBodyBytes);
        status = "201 Created";
      }
    }

    byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
    String acceptEncoding = context.headers.getOrDefault("accept-encoding", "");
    if (acceptEncoding.toLowerCase().contains("gzip")) {
      contentEncoding = "gzip";
      bodyBytes = gzip(bodyBytes);
    }

    if ("404 Not Found".equals(status) && responseBody.isEmpty()) {
      return new Response("HTTP/1.1 404 Not Found\r\n\r\n");
    }
    if ("201 Created".equals(status) && responseBody.isEmpty()) {
      return new Response("HTTP/1.1 201 Created\r\n\r\n");
    }

    return new Response(buildHeaders(status, contentType, contentEncoding, bodyBytes.length), bodyBytes);
  }

  private static byte[] buildHeaders(String status, String contentType, String contentEncoding, int contentLength) {
    StringBuilder headersBuilder = new StringBuilder();
    headersBuilder.append("HTTP/1.1 ").append(status).append("\r\n");
    headersBuilder.append("Content-Type: ").append(contentType).append("\r\n");
    headersBuilder.append("Content-Length: ").append(contentLength).append("\r\n");
    if (!contentEncoding.isEmpty()) {
      headersBuilder.append("Content-Encoding: ").append(contentEncoding).append("\r\n");
    }
    headersBuilder.append("\r\n");
    return headersBuilder.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String getMethodFromRequestLine(String requestLine) {
    if (requestLine == null) {
      return "";
    }
    String[] parts = requestLine.split(" ");
    return parts.length >= 2 ? parts[0] : "";
  }

  private static String getPathFromRequestLine(String requestLine) {
    if (requestLine == null) {
      return "";
    }
    String[] parts = requestLine.split(" ");
    return parts.length >= 2 ? parts[1] : "";
  }

  private static Map<String, String> readHeaders(InputStream inputStream) throws IOException {
    Map<String, String> headers = new java.util.HashMap<>();
    String line;
    while ((line = readLine(inputStream)) != null && !line.isEmpty()) {
      int colonIndex = line.indexOf(':');
      if (colonIndex != -1) {
        String key = line.substring(0, colonIndex).trim().toLowerCase();
        String value = line.substring(colonIndex + 1).trim();
        headers.put(key, value);
      }
    }
    return headers;
  }

  private static int getContentLength(Map<String, String> headers) {
    String value = headers.get("content-length");
    if (value == null) {
      return 0;
    }
    return Integer.parseInt(value.trim());
  }

  private static String readLine(InputStream inputStream) throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      int byteValue = inputStream.read();
      if (byteValue == -1) {
        return line.length() == 0 ? null : line.toString();
      }
      if (byteValue == '\r') {
        int next = inputStream.read();
        if (next == '\n') {
          return line.toString();
        }
        line.append((char) byteValue);
        if (next != -1) {
          line.append((char) next);
        }
        continue;
      }
      if (byteValue == '\n') {
        return line.toString();
      }
      line.append((char) byteValue);
    }
  }

  private static byte[] readBody(InputStream inputStream, int contentLength) throws IOException {
    byte[] buffer = new byte[contentLength];
    int totalRead = 0;
    while (totalRead < contentLength) {
      int read = inputStream.read(buffer, totalRead, contentLength - totalRead);
      if (read == -1) {
        break;
      }
      totalRead += read;
    }
    return java.util.Arrays.copyOf(buffer, totalRead);
  }

  private static byte[] gzip(byte[] input) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
      gzipOutputStream.write(input);
    }
    return outputStream.toByteArray();
  }

  private static final class RequestContext {
    final String method;
    final String path;
    final Map<String, String> headers;
    final String directory;
    final InputStream inputStream;

    private RequestContext(String method, String path, Map<String, String> headers, String directory, InputStream inputStream) {
      this.method = method;
      this.path = path;
      this.headers = headers;
      this.directory = directory;
      this.inputStream = inputStream;
    }
  }

  private static final class Response {
    private final byte[] headers;
    private final byte[] body;

    private Response(String rawResponse) {
      this.headers = rawResponse.getBytes(StandardCharsets.UTF_8);
      this.body = new byte[0];
    }

    private Response(byte[] headers, byte[] body) {
      this.headers = headers;
      this.body = body;
    }

    private byte[] toBytes() {
      byte[] fullResponse = new byte[headers.length + body.length];
      System.arraycopy(headers, 0, fullResponse, 0, headers.length);
      System.arraycopy(body, 0, fullResponse, headers.length, body.length);
      return fullResponse;
    }
  }
}
